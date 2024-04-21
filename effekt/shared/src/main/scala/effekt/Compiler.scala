package effekt

import effekt.context.Context
import effekt.namer.Namer
import effekt.source.{ AnnotateCaptures, ExplicitCapabilities, ModuleDecl }
import effekt.symbols.Module
import effekt.typer.{ Typer }
import effekt.util.messages.FatalPhaseError
import effekt.util.{ SourceTask, Task, VirtualSource, paths }
import kiama.output.PrettyPrinterTypes.Document
import kiama.util.{ Positions, Source }

/**
 * Intermediate results produced by the various phases.
 *
 * All phases have a source field, which is mostly used to invalidate caches based on the timestamp.
 */
enum PhaseResult {

  val source: Source

  /**
   * The result of [[Parser]] parsing a single file into a [[effekt.source.Tree]].
   */
  case Parsed(source: Source, tree: ModuleDecl)

  /**
   * The result of [[Namer]] resolving all names in a given syntax tree. The resolved symbols are
   * annotated in the [[Context]] using [[effekt.context.Annotations]].
   */
  case NameResolved(source: Source, tree: ModuleDecl, mod: symbols.Module)

  /**
   * The result of [[Typer]] type checking a given syntax tree.
   *
   * We can notice that [[NameResolved]] and [[Typechecked]] haave the same fields.
   * Like, [[Namer]], [[Typer]] writes to the types of each tree into the DB, using [[effekt.context.Annotations]].
   * This might change in the future, when we switch to elaboration.
   */
  case Typechecked(source: Source, tree: ModuleDecl, mod: symbols.Module)

}
export PhaseResult.*

enum Stage { case Core; case Target; }

/**
 * The compiler for the Effekt language.
 *
 * The compiler is set up in the following large phases that consist itself of potentially multiple phases
 *
 *   1. Parser    (Source      -> source.Tree)  Load file and parse it into an AST
 *
 *   2. Frontend  (source.Tree -> source.Tree)  Perform name analysis, typechecking, region inference,
 *                                             and other rewriting of the source AST
 *
 *   3. Middleend (source.Tree -> core.Tree)    Perform an ANF transformation into core, and
 *                                             other rewritings on the core AST
 *
 *   4. Backend  (core.Tree   -> Document)     Generate code in a target language
 *
 * The compiler itself does not read from or write to files. This is important since, we need to
 * virtualize the file system to also run the compiler in the browser.
 *
 * - Reading files is performed by the mixin [[effekt.context.ModuleDB]], which is implemented
 *   differently for the JS and JVM versions.
 * - Writing to files is performed by the hook [[Compiler.saveOutput]], which is implemented
 *   differently for the JS and JVM versions.
 *
 * Backends that implement this interface:
 *
 * - [[generator.js.JavaScript]]
 * - [[generator.chez.ChezScheme]] (in three variants)
 * - [[generator.llvm.LLVM]]
 * - [[generator.ml.ML]]
 *
 * @tparam Executable information of this compilation run, which is passed to
 *                 the corresponding backend runner (e.g. the name of the main file)
 */
trait Compiler[Executable] {

  // The Compiler Interface:
  // -----------------------
  // Used by Driver, Server, Repl and Web

  def extension: String

  /**
   * Used by LSP server (Intelligence) to map positions to source trees
   */
  def getAST(source: Source)(using Context): Option[ModuleDecl] =
    CachedParser(source).map { res => res.tree }

  /**
   * Used by
   * - Namer to resolve dependencies
   * - Server / Driver to typecheck and report type errors in VSCode
   */
  def runFrontend(source: Source)(using Context): Option[Module] =
    Frontend(source).map { res =>
      val mod = res.mod
      validate(source, mod)
      mod
    }


  /**
   * Called after running the frontend from editor services.
   *
   * Can be overridden to implement backend specific checks (exclude certain
   * syntactic constructs, etc.)
   */
  def validate(source: Source, mod: Module)(using Context): Unit = ()

  /**
   * Show the IR of this backend for [[source]] after [[stage]].
   * Backends can return [[None]] if they do not support this.
   *
   * Used to show the IR in VSCode. For the JS Backend, also used for
   * separate compilation in the web.
   */
  def prettyIR(source: Source, stage: Stage)(using Context): Option[Document]

  /**
   * Return the backend specific AST for [[source]] after [[stage]].
   * Backends can return [[None]] if they do not support this.
   *
   * The AST will be pretty printed with a generic [[effekt.util.PrettyPrinter]].
   */
  def treeIR(source: Source, stage: Stage)(using Context): Option[Any]

  /**
   * Should compile [[source]] with this backend. Each backend can
   * choose the representation of the executable.
   */
  def compile(source: Source)(using Context): Option[(Map[String, String], Executable)]

  val CachedParser: Phase[Source, Parsed] = ???

  /**
   * Frontend
   */
  val Frontend = Phase.cached("frontend") {
    /**
     * Parses a file to a syntax tree
     *   [[Source]] --> [[Parsed]]
     */
    CachedParser andThen
      /**
       * Performs name analysis and associates Id-trees with symbols
       *    [[Parsed]] --> [[NameResolved]]
       */
      Namer andThen
      /**
       * Type checks and annotates trees with inferred types and effects
       *   [[NameResolved]] --> [[Typechecked]]
       */
      Typer
  }


  // Helpers
  // -------
  import effekt.util.paths.file

  /**
   * Path relative to the output folder
   */
  def path(m: symbols.Module)(using C: Context): String =
    (m.path.replace('/', '_').replace('-', '_')) + extension
}
