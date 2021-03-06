package xitrum.view

import java.io.{File, PrintWriter, StringWriter}
import java.text.{DateFormat, NumberFormat}
import java.util.Locale

import scala.util.parsing.input.NoPosition

import org.fusesource.scalate.{
  Binding,
  DefaultRenderContext,
  InvalidSyntaxException,
  RenderContext,
  Template,
  TemplateEngine => STE
}
import org.fusesource.scalate.scaml.ScamlOptions

import com.esotericsoftware.reflectasm.ConstructorAccess
import org.jboss.netty.handler.codec.serialization.ClassResolvers

import xitrum.{Config, Action, Log}

/**
 * This class is intended for use only by Xitrum. Apps that want to create
 * additional Scalate template engine instances can use [[ScalateEngine]].
 */
class Scalate extends ScalateEngine(
  "src/main/scalate",
  !Config.productionMode,
  Config.xitrum.config.getString("template.\"" + classOf[Scalate].getName + "\".defaultType")
) {
  override def start() {
    // Scalate takes several seconds to initialize => Warm it up here

    val dummyAction = new Action {
      def execute() {}
    }

    renderJadeString("")(dummyAction)
    renderMustacheString("")(dummyAction)
    renderScamlString("")(dummyAction)
    renderSspString("")(dummyAction)

    // Can't warmup Scalate.renderTemplateFile:
    // https://github.com/xitrum-framework/xitrum-scalate/issues/6
  }
}

object ScalateEngine {
  val WORKDIR = Config.xitrum.tmpDir.getAbsolutePath + File.separator + "scalate"

  val ACTION_BINDING_ID  = "helper"
  val CONTEXT_BINDING_ID = "context"
  val CLASS_RESOLVER     = ClassResolvers.softCachingConcurrentResolver(getClass.getClassLoader)

  System.setProperty("scalate.workdir", WORKDIR)
  ScamlOptions.ugly = Config.productionMode

  /** Puts error line right in the exception message so that Xitrum can simply display it. */
  def invalidSyntaxExceptionWithErrorLine(e: InvalidSyntaxException): InvalidSyntaxException = {
    val pos = e.pos
    if (pos == NoPosition) {
      e
    } else {
      val errorLine = e.source.uri + "\n" + pos.longString
      val eWithErrorLine = new InvalidSyntaxException(e.brief + "\n" + errorLine, pos)
      eWithErrorLine.source = e.source
      eWithErrorLine
    }
  }

  def exceptionWithErrorLine(e: Throwable, templateUri: String, templateContent: String = ""): Throwable = {
    val generatedScalaFileUri = WORKDIR + "/src/" + templateUri + ".scala"
    val file = new File(generatedScalaFileUri)
    if (file.exists) {
      val src       = srcWithLineNumbers(scala.io.Source.fromFile(file).getLines)
      val errorLine = e.getStackTrace()(0).getLineNumber
      val msg       =
        if (templateContent.isEmpty)
          e.toString + "\n" +
          templateUri + "\n" +
          generatedScalaFileUri + ":" + errorLine + "\n" +
          src
        else
          e.toString + "\n" +
          templateUri + "\n" +
          srcWithLineNumbers(templateContent.split('\n').iterator) + "\n" +
          generatedScalaFileUri + ":" + errorLine + "\n" +
          src
      new RuntimeException(msg, e)
    } else {
      e
    }
  }

  def srcWithLineNumbers(lineIt: Iterator[String]): String = {
    val builder = new StringBuilder
    var lineNum = 1
    while (lineIt.hasNext) {
      builder.append("%4d  ".format(lineNum))
      builder.append(lineIt.next)
      builder.append("\n")
      lineNum += 1
    }
    builder.toString
  }
}

/**
 * This class is intended for use by both Xitrum and normal apps to create
 * additional Scalate template engine instances.
 *
 * @param allowReload Template files in templateDir will be reloaded every time
 * @param defaultType "jade", "mustache", "scaml", or "ssp"
 */
class ScalateEngine(
  templateDirUri: String, allowReload: Boolean, defaultType: String
) extends TemplateEngine
  with ScalateEngineRenderInterface
  with ScalateEngineRenderTemplate
  with ScalateEngineRenderString
  with Log
{
  import ScalateEngine._

  protected[this] val fileEngine = createEngine(allowCaching = true, allowReload)

  // No need to cache or reload for stringEngine.
  protected[this] val stringEngine = createEngine(allowCaching = false, allowReload = false)

  protected def createEngine(allowCaching: Boolean, allowReload: Boolean): STE = {
    val ret          = new STE
    ret.allowCaching = allowCaching
    ret.allowReload  = allowReload
    ret.bindings     = List(
      // import things in the current action
      Binding(ACTION_BINDING_ID, classOf[Action].getName, importMembers = true),

      // import Scalate utilities like "unescape"
      Binding(CONTEXT_BINDING_ID, classOf[RenderContext].getName, importMembers = true)
    )

    ret
  }

  //----------------------------------------------------------------------------
  // TemplateEngine interface methods (see also ScalateEngineRenderInterface)

  override def start() {}

  override def stop() {
    fileEngine.shutdown()
    stringEngine.shutdown()
  }

  //----------------------------------------------------------------------------

  /**
   * Takes out "type" from options. It shoud be one of:
   * "jade", "mustache", "scaml", or "ssp"
   */
  protected def templateType(options: Map[String, Any]): String =
    options.getOrElse("type", defaultType).asInstanceOf[String]

  /** Takes out "date" format from options. */
  protected def dateFormat(options: Map[String, Any]): Option[DateFormat] =
    options.get("date").map(_.asInstanceOf[DateFormat])

  /** Takes out "number" format from options. */
  protected def numberFormat(options: Map[String, Any]): Option[NumberFormat] =
    options.get("number").map(_.asInstanceOf[NumberFormat])

  /**
   * If "date" (java.text.DateFormat) or "number" (java.text.NumberFormat)
   * is not set in "options", the format corresponding to current language in
   * "currentAction" will be used.
   */
  protected def createContext(
    templateUri: String, engine: STE,
    currentAction: Action, options: Map[String, Any]): ScalateRenderContext =
    {
      val context = new ScalateRenderContext(templateUri, engine, currentAction)
      val attributes = context.attributes

      // For bindings in engine
      attributes.update(ACTION_BINDING_ID, currentAction)
      attributes.update(CONTEXT_BINDING_ID, context)

    // Put action.at to context
    currentAction.at.foreach { case (k, v) =>
      if (k == ACTION_BINDING_ID || k == CONTEXT_BINDING_ID)
        log.warn(
          ACTION_BINDING_ID + " and " + CONTEXT_BINDING_ID +
          " are reserved key names for action's \"at\""
        )
      else
        attributes.update(k, v)
    }

    setFormats(context, currentAction, options)
    context
  }

  protected def setFormats(context: RenderContext, currentAction: Action, options: Map[String, Any]) {
    context.dateFormat   = dateFormat(options).getOrElse(DateFormat.getDateInstance(DateFormat.DEFAULT, currentAction.locale))
    context.numberFormat = numberFormat(options).getOrElse(NumberFormat.getInstance(currentAction.locale))
  }

  //----------------------------------------------------------------------------

  /**
   * Production mode: Renders the precompiled template class.
   * Development mode: Renders Scalate template file relative to templateDir.
   * If the file does not exist, falls back to rendering the precompiled template class.
   *
   * @param currentAction Will be imported in the template as "helper"
   */
  protected def renderMaybePrecompiledFile(relUri: String, currentAction: Action, options: Map[String, Any]): String = {
    if (Config.productionMode)
      renderPrecompiledFile(relUri, currentAction, options)
    else
      renderNonPrecompiledFile(relUri, currentAction, options)
  }

  protected def renderPrecompiledFile(relUri: String, currentAction: Action, options: Map[String, Any]): String = {
    // In production mode, after being precompiled,
    // quickstart/action/AppAction.jade will become
    // class scalate.quickstart.action.$_scalate_$AppAction_jade
    val withDots     = relUri.replace('/', '.')
    val xs           = withDots.split('.')
    val extension    = xs.last
    val baseFileName = xs(xs.length - 2)
    val prefix       = xs.take(xs.length - 2).mkString(".")
    val className    = "scalate." + prefix + ".$_scalate_$" + baseFileName + "_" + extension
    val klass        = CLASS_RESOLVER.resolve(className)
    val template     = ConstructorAccess.get(klass).newInstance().asInstanceOf[Template]

    renderTemplate(template, relUri, options)(currentAction)
  }

  protected def renderNonPrecompiledFile(relUri: String, currentAction: Action, options: Map[String, Any]): String = {
    val uri = templateDirUri + "/" + relUri
    val file = new File(uri)
    if (file.exists) {
      renderTemplateFile(uri, options)(currentAction)
    } else {
      // If called from a JAR library, the template may have been precompiled
      renderPrecompiledFile(relUri, currentAction, options)
    }
  }
}
