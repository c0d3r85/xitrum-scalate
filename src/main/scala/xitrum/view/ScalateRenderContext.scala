package xitrum.view

import org.fusesource.scalate.DefaultRenderContext
import java.io.StringWriter
import java.io.PrintWriter
import org.fusesource.scalate.{ TemplateEngine => STE }
import org.fusesource.scalate.Template
import java.util.Locale
import java.io.Writer
import xitrum.Action

class ScalateRenderContext(_requestUri: String,
                           override val engine: STE,
                           currentAction: Action,
                           val buffer: Writer = new StringWriter) extends DefaultRenderContext(_requestUri, engine, new PrintWriter(buffer)) {
  override def locale: Locale = currentAction.locale
}

