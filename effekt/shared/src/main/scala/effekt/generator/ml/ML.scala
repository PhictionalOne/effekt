package effekt
package generator
package ml

import effekt.context.Context
import effekt.lifted.LiftInference
import kiama.output.PrettyPrinterTypes.Document
import kiama.util.Source


class ML extends Compiler[String] {

  // Implementation of the Compiler Interface:
  // -----------------------------------------
  def extension = ".sml"

  override def prettyIR(source: Source, stage: Stage)(using Context): Option[Document] = stage match {
    case Stage.Core => steps.afterCore(source).map { res => core.PrettyPrinter.format(res.core) }
    case Stage.Lifted => steps.afterLift(source).map { res => lifted.PrettyPrinter.format(res.core) }
    case Stage.Machine => None
    case Stage.Target => steps.afterML(source).map { res => pretty(res) }
  }

  override def treeIR(source: Source, stage: Stage)(using Context): Option[Any] = stage match {
    case Stage.Core => steps.afterCore(source).map { res => res.core }
    case Stage.Lifted => steps.afterLift(source).map { res => res.core }
    case Stage.Machine => None
    case Stage.Target => steps.afterML(source)
  }

  override def compile(source: Source)(using C: Context) = Compile(source)


  // The Compilation Pipeline
  // ------------------------
  // Source => Core => Lifted => ML
  lazy val Compile = allToCore(Core) andThen Aggregate andThen LiftInference andThen ToML map {
    case (mainFile, prog) => (Map(mainFile -> pretty(prog)), mainFile)
  }

  lazy val Core = Phase.cached("core") {
    Frontend andThen Middleend
  }

  lazy val ToML = Phase("ml") {
    case PhaseResult.CoreLifted(source, tree, mod, core) =>
      val mainSymbol = Context.checkMain(mod)
      val mainFile = path(mod)
      mainFile -> ml.Transformer.compilationUnit(mainSymbol, core)
  }


  // The Compilation Pipeline for VSCode
  // -----------------------------------
  object steps {
    // intermediate steps for VSCode
    val afterCore = allToCore(Core) map { c => c.main }
    val afterLift = afterCore andThen LiftInference
    val afterML = afterLift andThen ToML map { case (f, prog) => prog }
  }

  // Helpers
  // -------
  private def pretty(prog: ml.Toplevel) =
    ml.PrettyPrinter.format(ml.PrettyPrinter.toDoc(prog))
}
