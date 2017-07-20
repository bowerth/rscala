package org.ddahl.rscala.server

import scala.annotation.tailrec
import scala.tools.nsc.interpreter.{IMain,ILoop}
import scala.tools.nsc.interpreter.IR.Success
import scala.tools.nsc.Settings
import java.net._
import java.io._

import org.ddahl.rscala._
import Protocol._
 
class ScalaServer private (private[rscala] val repl: IMain, pw: PrintWriter, baosOut: ByteArrayOutputStream, baosErr: ByteArrayOutputStream, portsFilename: String, debugger: Debugger, serializeOutput: Boolean, rowMajor: Boolean, port: Int) {

  private val sockets = new ScalaSockets(portsFilename,port,debugger)
  import sockets.{in, out, socketIn, socketOut}
  import Helper.{isMatrix, transposeIf}

  out.writeInt(OK)
  out.flush
  private val R = RClient(this,null,in,out,debugger,serializeOutput,rowMajor)
  if ( repl.bind("R","org.ddahl.rscala.RClient",R) != Success ) sys.error("Problem binding R.")
  if ( repl.interpret("import org.ddahl.rscala.{EphemeralReference, PersistentReference}") != Success ) sys.error("Problem interpreting import statement.")
  if ( serializeOutput ) {
    baosOut.reset
    baosErr.reset
  }

  private[rscala] val cacheMap = new Cache()

  private var functionResult: (Any, String) = null
  private val nullary = Class.forName("scala.Function0").getMethod("apply")
  nullary.setAccessible(true)
  private val functionMap = new scala.collection.mutable.HashMap[String,(Any,String)]()

  private def typeOfTerm(id: String) = repl.symbolOfLine(id).info.toString

  private def mostRecentVar = {
    val mrv = repl.mostRecentVar
    if ( mrv == "_rsMRVDummy" ) throw new RuntimeException("There is no result.  Maybe use the %@% operator instead?")
    mrv
  }

  private def extractReturnType(signature: String) = {
    var squareCount = 0
    var parenCount = 0
    var i = 0
    val s = signature.trim
    while ( ( ( parenCount > 0 ) || ( squareCount > 0 ) || ( ! ( ( s(i) == '=' ) && ( s(i+1) == '>' ) ) ) ) && ( i < s.length ) ) {
      val c = s(i)
      i += 1
      if ( c == '(' ) parenCount += 1
      if ( c == ')' ) parenCount -= 1
      if ( c == '[' ) squareCount += 1
      if ( c == ']' ) squareCount -= 1
    }
    if ( i == s.length ) throw new IllegalArgumentException("Unexpected end of signature."+signature)
    i += 2
    val r = s.substring(i).trim
    if ( r.length == 0 ) throw new IllegalArgumentException("Unexpected end of signature."+signature)
    r
  }

  private def readString() = Helper.readString(in)
  private def writeString(string: String): Unit = {
    if ( debugger.value ) debugger.msg("Writing string: <"+string+">")
    Helper.writeString(out,string)
  }

  private def setAVM(identifier: String, t: String, v: Any): Unit = {
    if ( debugger.value ) debugger.msg("Value is "+v)
    repl.bind(identifier,t,v)
  }

  private val sep = sys.props("line.separator")

  private def doFree(): Unit = {
    val nItems = in.readInt()
    if ( debugger.value ) debugger.msg("Freeing "+nItems+" cached items no longer needed by R interpreter.")
    for ( i <- 0 until nItems ) cacheMap.free(readString())
  }

  private def doEval(): Unit = {
    val snippet = "var _rsMRVDummy = null\n" + readString()
    try {
      val result = repl.interpret(snippet)
      if ( result == Success ) {
        if ( debugger.value ) debugger.msg("Eval is okay.")
        R.exit()
        out.writeInt(OK)
      } else {
        if ( debugger.value ) debugger.msg("Eval had a parse error.")
        R.exit()
        out.writeInt(ERROR)
      }
    } catch {
      case e: Throwable =>
        if ( debugger.value ) debugger.msg("Caught throwable: "+e.toString)
        R.exit()
        out.writeInt(ERROR)
        e.printStackTrace(pw)
        pw.println(e + ( if ( e.getCause != null ) System.lineSeparator + e.getCause else "" ) )
    }
  }

  private def doDef(): Unit = {
    try {
      val functionName = readString()
      val functionReturnType = readString()
      functionMap(functionName) = (repl.valueOfTerm(functionName).get, functionReturnType)
      out.writeInt(OK)
    } catch {
      case e: Throwable =>
        out.writeInt(ERROR)
        e.printStackTrace(pw)
        pw.println(e + ( if ( e.getCause != null ) System.lineSeparator + e.getCause else "" ) )
    }
  }

  private def doInvoke(): Unit = {
    try {
      val functionName = readString()
      val (f, returnType) = functionMap(functionName)
      if ( debugger.value ) {
        debugger.msg("Function is: "+functionName)
        debugger.msg("... with return type: "+returnType)
      }
      functionResult = (nullary.invoke(f), returnType)
      R.exit()
      if ( debugger.value ) debugger.msg("Invoke is okay")
      out.writeInt(OK)
    } catch {
      case e: Throwable =>
        R.exit()
        out.writeInt(ERROR)
        e.printStackTrace(pw)
        pw.println(e + ( if ( e.getCause != null ) System.lineSeparator + e.getCause else "" ) )
    }
  }

  private val RegexpVar = """^\s+(?:final )?\s*(?:override )?\s*var\s+([^(:]+):\s+([^=;]+).*""".r
  private val RegexpVal = """^\s+(?:final )?\s*(?:override )?\s*val\s+([^(:]+):\s+([^=;]+).*""".r
  private val RegexpDefNoParen = """^\s+(?:final )?\s*(?:override)?\s*def\s+([a-zA-Z0-9]+)(?:\[.*\]:|:)\s+([^=;]+).*""".r
  private val RegexpDefWithParen = """^\s+(?:final )?\s*(?:override)?\s*def\s+([a-zA-Z0-9]+)(?:\[.*\]+)?\((.*)\):\s+([^=]+).*""".r
  private val RegexpConstructor = """^\s+def\s+this\((.*)\).*""".r
  private val RegexpThrows = """^\s*throws\s+.*""".r
  private val rscalaReference = "rscalaReference"

  private def parseArgNames(args: String): Array[String] = {
    if ( args.contains(")(") ) null
    else {
      val x = args.split(":")
      val y = if ( x.length > 1 ) x.dropRight(1) else x
      y.map(z => {
        val i = z.lastIndexOf(",")
        if ( i >= 0 ) z.substring(i+1).trim else z.trim
      })
    }
  }

  private def doScalap(): Unit = {
    if ( debugger.value ) debugger.msg("Doing scalap...")
    val itemName = readString()
    if ( debugger.value ) debugger.msg("... on "+itemName)
    val classpath = sys.props("sun.boot.class.path") + sys.props("path.separator") + sys.props("rscala.classpath")
    if ( debugger.value ) debugger.msg("... with classpath "+classpath)
    scala.tools.scalap.Main.main(Array("-cp",classpath,itemName))
    out.writeInt(OK)
  }

  private def doSet(): Unit = {
    val identifier = readString()
    in.readInt() match {
      case NULLTYPE =>
        if ( debugger.value ) debugger.msg("Setting null.")
        repl.bind(identifier,"Any",null)
      case REFERENCE =>
        if ( debugger.value ) debugger.msg("Setting reference.")
        val originalIdentifier = readString()
        try {
          val (value,tipe) = originalIdentifier match {
            case cacheMap(value,typeOfTerm) => (value,typeOfTerm)
            case _ => (repl.valueOfTerm(originalIdentifier).get,typeOfTerm(originalIdentifier))
          }
          if ( repl.bind(identifier,tipe,value) != Success ) throw new RuntimeException("Cannot set reference.")
          out.writeInt(OK)
        } catch {
          case e: Throwable =>
            if ( debugger.value ) debugger.msg("Caught exception: "+e)
            out.writeInt(ERROR)
            e.printStackTrace(pw)
            pw.println(e + ( if ( e.getCause != null ) System.lineSeparator + e.getCause else "" ) )
        }
      case SCALAR =>
        if ( debugger.value ) debugger.msg("Setting atomic.")
        val (v: Any, t: String) = in.readInt() match {
          case INTEGER => (in.readInt(),"Int")
          case DOUBLE => (in.readDouble(),"Double")
          case BOOLEAN => (( in.readInt() != 0 ),"Boolean")
          case STRING => (readString(),"String")
          case BYTE => (in.readByte(),"Byte")
          case _ => throw new RuntimeException("Protocol error")
        }
        setAVM(identifier,t,v)
      case VECTOR =>
        if ( debugger.value ) debugger.msg("Setting vector...")
        val length = in.readInt()
        if ( debugger.value ) debugger.msg("... of length: "+length)
        val (v, t): (Any,String) = in.readInt() match {
          case INTEGER => (Array.fill(length) { in.readInt() },"Array[Int]")
          case DOUBLE => (Array.fill(length) { in.readDouble() },"Array[Double]")
          case BOOLEAN => (Array.fill(length) { ( in.readInt() != 0 ) },"Array[Boolean]")
          case STRING => (Array.fill(length) { readString() },"Array[String]")
          case BYTE => (Array.fill(length) { in.readByte() },"Array[Byte]")
          case _ => throw new RuntimeException("Protocol error")
        }
        setAVM(identifier,t,v)
      case MATRIX =>
        if ( debugger.value ) debugger.msg("Setting matrix...")
        val nrow = in.readInt()
        val ncol = in.readInt()
        if ( debugger.value ) debugger.msg("... of dimensions: "+nrow+","+ncol)
        val (v, t): (Any,String) = in.readInt() match {
          case INTEGER => ( transposeIf( Array.fill(nrow) { Array.fill(ncol) { in.readInt() } }, rowMajor),"Array[Array[Int]]")
          case DOUBLE => ( transposeIf( Array.fill(nrow) { Array.fill(ncol) { in.readDouble() } }, rowMajor),"Array[Array[Double]]")
          case BOOLEAN => ( transposeIf( Array.fill(nrow) { Array.fill(ncol) { ( in.readInt() != 0 ) } }, rowMajor),"Array[Array[Boolean]]")
          case STRING => ( transposeIf( Array.fill(nrow) { Array.fill(ncol) { readString() } }, rowMajor),"Array[Array[String]]")
          case BYTE => ( transposeIf( Array.fill(nrow) { Array.fill(ncol) { in.readByte() } }, rowMajor),"Array[Array[Byte]]")
          case _ => throw new RuntimeException("Protocol error")
        }
        setAVM(identifier,t,v)
      case _ => throw new RuntimeException("Protocol error")
    }
  }

  private def doGet(): Unit = {
    val identifier = readString()
    if ( debugger.value ) debugger.msg("Trying to get value of: "+identifier)
    val optionWithType = try {
      identifier match {
        case "." => 
          val mrv = mostRecentVar
          (repl.valueOfTerm(mrv),typeOfTerm(mrv))
        case "?" => 
          (Some(functionResult._1),functionResult._2)
        case cacheMap(value,typeOfTerm) =>
          (Some(value),typeOfTerm)
        case "null" =>
          (Some(null),"Null")
        case _ =>
          (repl.valueOfTerm(identifier),typeOfTerm(identifier))
      }
    } catch {
      case e: Throwable =>
        if ( debugger.value ) debugger.msg("Caught exception: "+e)
        out.writeInt(ERROR)
        e.printStackTrace(pw)
        pw.println(e + ( if ( e.getCause != null ) System.lineSeparator + e.getCause else "" ) )
        return
    }
    if ( debugger.value ) debugger.msg("Getting: "+identifier)
    if ( optionWithType._1.isEmpty ) {
      if ( optionWithType._2 == "<notype>" ) {
        if ( debugger.value ) debugger.msg("... which does not exist.")
        out.writeInt(UNDEFINED_IDENTIFIER)
        return
      } else {
        if ( debugger.value ) debugger.msg("... which is emtpy [due to quirk in 2.10 series].")
        out.writeInt(NULLTYPE)
        return
      }
    }
    val v = optionWithType._1.get
    if ( v == null || v.isInstanceOf[Unit] ) {
      if ( debugger.value ) debugger.msg("... which is null")
      out.writeInt(NULLTYPE)
      return
    }
    val t = if ( optionWithType._2 == "Any" ) {
      val c = v.getClass
      if ( debugger.value ) debugger.msg("Trying to infer type of "+c)
      if ( c.isArray ) {
        c.getName match {
          case "[I" => "Array[Int]"
          case "[D" => "Array[Double]"
          case "[Z" => "Array[Boolean]"
          case "[Ljava.lang.String;" => "Array[String]"
          case "[B" => "Array[Byte]"
          case "[[I" => "Array[Array[Int]]"
          case "[[D" => "Array[Array[Double]]"
          case "[[Z" => "Array[Array[Boolean]]"
          case "[[Ljava.lang.String;" => "Array[Array[String]]"
          case "[[B" => "Array[Array[Byte]]"
          case _ => "Any"
        }
      } else {
        c.getName match {
          case "java.lang.Integer" => "Int"
          case "java.lang.Double" => "Double"
          case "java.lang.Boolean" => "Boolean"
          case "java.lang.String" => "String"
          case "java.lang.Byte" => "Byte"
          case _ => "Any"
        }
      }
    } else optionWithType._2
    if ( debugger.value ) debugger.msg("... whose type is: "+t)
    if ( debugger.value ) debugger.msg("... and whose value is: "+v)
    t match {
      case "Array[Int]" =>
        val vv = v.asInstanceOf[Array[Int]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(INTEGER)
        for ( i <- 0 until vv.length ) out.writeInt(vv(i))
      case "Array[Double]" =>
        val vv = v.asInstanceOf[Array[Double]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(DOUBLE)
        for ( i <- 0 until vv.length ) out.writeDouble(vv(i))
      case "Array[Boolean]" =>
        val vv = v.asInstanceOf[Array[Boolean]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(BOOLEAN)
        for ( i <- 0 until vv.length ) out.writeInt(if ( vv(i) ) 1 else 0)
      case "Array[String]" =>
        val vv = v.asInstanceOf[Array[String]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(STRING)
        for ( i <- 0 until vv.length ) writeString(vv(i))
      case "Array[Byte]" =>
        val vv = v.asInstanceOf[Array[Byte]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(BYTE)
        for ( i <- 0 until vv.length ) out.writeByte(vv(i))
      case "Array[Array[Int]]" =>
        val vv1 = v.asInstanceOf[Array[Array[Int]]]
        if ( isMatrix(vv1) ) {
          val vv = transposeIf(vv1, rowMajor)
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(INTEGER)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vvv.length ) {
              out.writeInt(vvv(j))
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Array[Array[Double]]" =>
        val vv1 = v.asInstanceOf[Array[Array[Double]]]
        if ( isMatrix(vv1) ) {
          val vv = transposeIf(vv1, rowMajor)
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(DOUBLE)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vvv.length ) {
              out.writeDouble(vvv(j))
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Array[Array[Boolean]]" =>
        val vv1 = v.asInstanceOf[Array[Array[Boolean]]]
        if ( isMatrix(vv1) ) {
          val vv = transposeIf(vv1, rowMajor)
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(BOOLEAN)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vv(i).length ) {
              out.writeInt(if ( vvv(j) ) 1 else 0)
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Array[Array[String]]" =>
        val vv1 = v.asInstanceOf[Array[Array[String]]]
        if ( isMatrix(vv1) ) {
          val vv = transposeIf(vv1, rowMajor)
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(STRING)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vv(i).length ) {
              writeString(vvv(j))
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Array[Array[Byte]]" =>
        val vv1 = v.asInstanceOf[Array[Array[Byte]]]
        if ( isMatrix(vv1) ) {
          val vv = transposeIf(vv1, rowMajor)
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(BYTE)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vvv.length ) {
              out.writeByte(vvv(j))
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Int" =>
        out.writeInt(SCALAR)
        out.writeInt(INTEGER)
        out.writeInt(v.asInstanceOf[Int])
      case "Double" =>
        out.writeInt(SCALAR)
        out.writeInt(DOUBLE)
        out.writeDouble(v.asInstanceOf[Double])
      case "Boolean" =>
        out.writeInt(SCALAR)
        out.writeInt(BOOLEAN)
        out.writeInt(if (v.asInstanceOf[Boolean]) 1 else 0)
      case "String" =>
        out.writeInt(SCALAR)
        out.writeInt(STRING)
        writeString(v.asInstanceOf[String])
      case "Byte" =>
        out.writeInt(SCALAR)
        out.writeInt(BYTE)
        out.writeByte(v.asInstanceOf[Byte])
      case _ =>
        if ( debugger.value ) debugger.msg("Bowing out: "+identifier)
        out.writeInt(UNSUPPORTED_STRUCTURE)
    }
  }

  private def doGetReference(): Unit = {
    val identifier = readString()
    if ( debugger.value ) debugger.msg("Trying to get reference for: "+identifier)
    identifier match {
      case "?" =>
        out.writeInt(OK)
        writeString(cacheMap.store(functionResult))
        writeString(functionResult._2)
      case cacheMap(value, typeOfTerm) =>
        out.writeInt(OK)
        writeString(identifier)
        writeString(typeOfTerm)
      case _ =>
        if ( identifier == "null" ) {
          out.writeInt(OK)
          writeString("null")
          writeString("Null")
          return
        }
        val (id, opt) = try {
          val ident = if ( identifier == "." ) mostRecentVar else identifier
          val option = repl.valueOfTerm(ident)
          (ident, option)
        } catch {
          case e: Throwable =>
            if ( debugger.value ) debugger.msg("Caught exception: "+e)
            out.writeInt(ERROR)
            e.printStackTrace(pw)
            pw.println(e + ( if ( e.getCause != null ) System.lineSeparator + e.getCause else "" ) )
            return
        }
        if ( opt.isEmpty ) out.writeInt(UNDEFINED_IDENTIFIER)
        else {
          out.writeInt(OK)
          writeString(id)
          writeString(typeOfTerm(id))
        }
    }
  }

  private def heart(request: Int): Boolean = {
    if ( debugger.value ) debugger.msg("Received request: "+request)
    request match {
      case SHUTDOWN =>
        if ( debugger.value ) debugger.msg("Shutting down")
        in.close()
        out.close()
        socketIn.close()
        socketOut.close()
        return false
      case EXIT =>
        if ( debugger.value ) debugger.msg("Exiting")
        return false
      case FREE =>
        doFree()
      case EVAL =>
        doEval()
      case SET =>
        doSet()
      case GET =>
        doGet()
      case GET_REFERENCE =>
        doGetReference()
      case DEF =>
        doDef()
      case INVOKE =>
        doInvoke()
      case SCALAP =>
        doScalap()
    }
    true
  }

  final def run(): Unit = {
    while ( true ) {
      if ( debugger.value ) debugger.msg("Scala server at top of the loop waiting for a command.")
      val request = try {
        in.readInt()
      } catch {
        case _: Throwable =>
          return
      }
      if ( serializeOutput ) {
        scala.Console.withOut(baosOut) {
          scala.Console.withErr(baosErr) {
            if ( ! heart(request) ) return
          }
        }
        pw.flush()
        writeString(baosOut.toString+baosErr.toString)
        baosOut.reset()
        baosErr.reset()
      } else {
        if ( ! heart(request) ) return
      }
      out.flush()
    }
  }

}

object ScalaServer {

  def apply(portsFilename: String, debug: Boolean = false, serializeOutput: Boolean = false, rowMajor: Boolean = true, port: Int = 0): ScalaServer = {
    // Set classpath
    val settings = new Settings()
    settings.embeddedDefaults[RClient]
    val urls = java.lang.Thread.currentThread.getContextClassLoader match {
      case cl: java.net.URLClassLoader => cl.getURLs.toList
      case _ => sys.error("Classloader is not a URLClassLoader")
    }
    val classpath = urls.map(_.getPath)
    settings.classpath.value = classpath.distinct.mkString(java.io.File.pathSeparator)
    settings.deprecation.value = true
    settings.feature.value = true
    settings.unchecked.value = true
    // Allow reflective calls without a warning since we make us of them.
    // Use refective since Scala 2.10.x doess not have the settings.language.add method.
    val m1 = if ( util.Properties.versionNumberString.split("""\.""").take(2).mkString(".") == "2.10" ) {
      settings.language.getClass.getMethod("appendToValue","".getClass)
    } else {
      settings.language.getClass.getMethod("add","".getClass)
    }
    m1.setAccessible(true)
    m1.invoke(settings.language,"reflectiveCalls")
    // A better way to do it, but Scala 2.10.x doess not the settings.language.add method.
    // settings.language.add("reflectiveCalls")
    // Set up sinks
    val (debugger,prntWrtr,baosOut,baosErr) = serializeOutput match {
      case true =>
        val out = new ByteArrayOutputStream()
        val err = new ByteArrayOutputStream()
        val pw = new PrintWriter(out)
        val d = new Debugger(debug,pw,"Scala",false)
        (d,pw,out,err)
      case false =>
        val pw = new PrintWriter(System.out,true)
        val d = new Debugger(debug,pw,"Scala",false)
        (d,pw,null,null)
    }
    if ( debugger.value ) debugger.msg("Classpath is:\n"+classpath.mkString("\n"))
    // Instantiate an interpreter
    val intp = new IMain(settings,prntWrtr)
    // Suppress output; equivalent to :silent in REPL, but it's private, so use reflection.
    val m2 = intp.getClass.getMethod("printResults_$eq",java.lang.Boolean.TYPE)
    m2.setAccessible(true)
    m2.invoke(intp,java.lang.Boolean.FALSE)
    // Another way to do it, but Scala 2.10.x is chatty and prints "Switched off result printing."
    // val iloop = new ILoop()
    // iloop.intp = intp
    // iloop.verbosity()
    // Return the server
    new ScalaServer(intp, prntWrtr, baosOut, baosErr, portsFilename, debugger, serializeOutput, rowMajor, port)
  }

}

