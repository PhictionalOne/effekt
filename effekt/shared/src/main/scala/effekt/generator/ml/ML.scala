package effekt
package generator
package ml

import effekt.context.Context
import effekt.lifted.*
import effekt.core.Id
import effekt.symbols.{ Symbol, TermSymbol, Module, Wildcard }

import effekt.util.paths.*
import kiama.output.PrettyPrinterTypes.Document

import scala.language.implicitConversions

/**
 * Lifted variant of Chez Scheme. Mostly copy-and-paste from [[ChezScheme]].
 *
 * Difficult to share the code, since core and lifted are different IRs.
 */
object ML extends Backend {

  def runMain(main: MLName): ml.Expr = CPS.runMain(main)

  /**
   * Returns [[Compiled]], containing the files that should be written to.
   */
  def compileWhole(main: CoreTransformed, mainSymbol: TermSymbol)(using C: Context): Option[Compiled] = {

    assert(main.core.imports.isEmpty, "All dependencies should have been inlined by now.")

    val mainSymbol = C.checkMain(main.mod)

    LiftInference(main).map { lifted =>
      val mlModule = compilationUnit(mainSymbol, lifted.core)
      val result = ml.PrettyPrinter.pretty(ml.PrettyPrinter.toDoc(mlModule), 100)
      val mainFile = path(main.mod)
      Compiled(main.source, mainFile, Map(mainFile -> result))
    }
  }

  /**
   * Entrypoint used by the LSP server to show the compiled output
   */
  def compileSeparate(input: AllTransformed)(using C: Context): Option[Document] =
    C.using(module = input.main.mod) {
      Some(ml.PrettyPrinter.format(ml.PrettyPrinter.toDoc(compile(input.main))))
    }

  /**
   * Compiles only the given module, does not compile dependencies
   */
  private def compile(in: CoreTransformed)(using Context): List[ml.Binding] =
    LiftInference(in).toList.flatMap { lifted => toML(lifted.core) }

  def compilationUnit(mainSymbol: Symbol, core: ModuleDecl)(implicit C: Context): ml.Toplevel = {
    ml.Toplevel(toML(core), runMain(name(mainSymbol)))
  }

  /**
   * This is used for both: writing the files to and generating the `require` statements.
   */
  def path(m: Module)(using C: Context): String =
    (C.config.outputPath() / m.path.replace('/', '_')).unixPath + ".sml"


  def toML(p: Param): MLName = name(p.id)

  def toML(e: Argument)(using Context): ml.Expr = e match {
    case e: lifted.Expr => toML(e)
    case b: lifted.Block => toML(b)
    case e: lifted.Evidence => toML(e)
  }

  def toML(module: ModuleDecl)(using Context): List[ml.Binding] = {
    val decls = module.decls.flatMap(toML)
    val externs = module.externs.map(toML)
    val rest = module.definitions.map(toML)
    decls ++ externs ++ rest
  }

  def tpeToML(tpe: BlockType)(using C: Context): ml.Type = tpe match {
    case BlockType.Function(tparams, eparams, vparams, bparams, ret) if tparams.nonEmpty =>
      C.abort("polymorphic functions not supported")
    case BlockType.Function(Nil, Nil, Nil, Nil, resType) =>
      ml.Type.Fun(List(ml.Type.Unit), tpeToML(resType))
    case BlockType.Function(Nil, Nil, vtpes, Nil, resType) =>
      ml.Type.Fun(vtpes.map(tpeToML), tpeToML(resType))
    case BlockType.Function(tparams, eparams, vparams, bparams, result) =>
      C.abort("higher order functions currently not supported")
    case BlockType.Interface(typeConstructor, args) =>
      ml.Type.TApp(ml.Type.Data(name(typeConstructor)), args.map(tpeToML))
  }

  def tpeToML(tpe: ValueType)(using Context): ml.Type = tpe match {
    case lifted.Type.TUnit => ml.Type.Unit
    case lifted.Type.TInt => ml.Type.Integer
    case lifted.Type.TDouble => ml.Type.Real
    case lifted.Type.TBoolean => ml.Type.Bool
    case lifted.Type.TString => ml.Type.String
    case ValueType.Var(id) => ml.Type.Var(name(id))
    case ValueType.Data(id, Nil) => ml.Type.Data(name(id))
    case ValueType.Data(id, args) => ml.Type.TApp(ml.Type.Data(name(id)), args.map(tpeToML))
    case ValueType.Boxed(tpe) => tpeToML(tpe)
  }

  def toML(decl: Declaration)(using C: Context): List[ml.Binding] = decl match {
    // TODO
    case Declaration.Data(id: symbols.TypeConstructor.Record, tparams, List(ctor)) =>
      recordRep(id, id.constructor, ctor.fields.map { f => f.id })

    case Data(id, tparams, ctors) =>
      def constructorToML(c: Constructor): (MLName, Option[ml.Type]) = c match {
        case Constructor(id, fields) =>
          val tpeList = fields.map { f => tpeToML(f.tpe) }
          val tpe = typesToTupleIsh(tpeList)
          (name(id), tpe)
      }

      val tvars: List[ml.Type.Var] = tparams.map(p => ml.Type.Var(name(p)))
      List(ml.Binding.DataBind(name(id), tvars, ctors map constructorToML))

    case Declaration.Interface(id, tparams, operations) =>
      recordRep(id, id, operations.map { op => op.id })
  }

  def recordRep(typeName: Id, caseName: Id, props: List[Id])(using Context): List[ml.Binding] = {
    // we introduce one type var for each property, in order to avoid having to translate types
    val tvars: List[ml.Type.Var] = props.map(_ => ml.Type.Var(freshName("arg")))
    val dataDecl = ml.Binding.DataBind(name(typeName), tvars, List((name(caseName), typesToTupleIsh(tvars))))
    val accessors = props.zipWithIndex.map {
      case (fieldName, i) =>
        // _, _, _, arg, _
        val patterns = props.indices.map {
          j => if j == i then ml.Pattern.Named(name(fieldName)) else ml.Pattern.Wild()
        }.toList
        val pattern = ml.Pattern.Datatype(name(caseName), patterns)
        val args = List(ml.Param.Patterned(pattern))
        val body = ml.Expr.Variable(name(fieldName))
        ml.Binding.FunBind(dataSelectorName(caseName, fieldName), args, body)
    }
    dataDecl :: accessors
  }

  def toML(ext: Extern)(using Context): ml.Binding = ext match {
    case Extern.Def(id, tparams, params, ret, body) =>
      ml.FunBind(name(id), params map (paramToML(_, false)), RawExpr(body))
    case Extern.Include(contents) =>
      RawBind(contents)
  }

  def paramToML(p: Param, unique: Boolean = true)(using Context): ml.Param = {
    val id = if (unique) name(p.id) else MLName(p.id.name.toString)
    ml.Param.Named(id)
  }

  def toMLExpr(stmt: Stmt)(using C: Context): CPS = stmt match {
    case lifted.Return(e) => CPS.pure(toML(e))

    case lifted.App(lifted.Member(lifted.BlockVar(x, _), symbols.builtins.TState.get, tpe), List(), List(ev)) =>
      CPS.pure(ml.Expr.Deref(ml.Variable(name(x))))

    case lifted.App(lifted.Member(lifted.BlockVar(x, _), symbols.builtins.TState.put, tpe), List(), List(ev, arg)) =>
      CPS.pure(ml.Expr.Assign(ml.Variable(name(x)), toML(arg)))

    case lifted.App(b, targs, args) => CPS.inline { k => ml.Expr.Call(ml.Expr.Call(toML(b), args map toML), List(k.reify)) }

    case lifted.If(cond, thn, els) =>
      CPS.join { k =>
        ml.If(toML(cond), toMLExpr(thn)(k), toMLExpr(els)(k))
      }

    case lifted.Val(id, binding, body) =>
      toMLExpr(binding).flatMap { value =>
        CPS.inline { k =>
          ml.mkLet(List(ml.ValBind(name(id), value)), toMLExpr(body)(k))
        }
      }

    case lifted.Match(scrutinee, clauses, default) => CPS.join { k =>
      def clauseToML(c: (Id, BlockLit)): ml.MatchClause = {
        val (id, b) = c
        val binders = b.params.map(p => ml.Pattern.Named(name(p.id)))
        val pattern = ml.Pattern.Datatype(name(id), binders)
        val body = toMLExpr(b.body)(k)
        ml.MatchClause(pattern, body)
      }

      ml.Match(toML(scrutinee), clauses map clauseToML, default map { d => toMLExpr(d)(k) })
    }

    // TODO maybe don't drop the continuation here? Although, it is dead code.
    case lifted.Hole() => CPS.inline { k => ml.Expr.RawExpr("raise Hole") }

    case lifted.Scope(definitions, body) => CPS.inline { k => ml.mkLet(definitions.map(toML), toMLExpr(body)(k)) }

    case lifted.State(id, init, region, ev, body) if region == symbols.builtins.globalRegion =>
      CPS.inline { k =>
        val bind = ml.Binding.ValBind(name(id), ml.Expr.Ref(toML(init)))
        ml.mkLet(List(bind), toMLExpr(body)(k))
      }

    case lifted.State(id, init, region, ev, body) =>
      CPS.inline { k =>
        val bind = ml.Binding.ValBind(name(id), ml.Call(ml.Consts.fresh)(ml.Variable(name(region)), toML(init)))
        ml.mkLet(List(bind), toMLExpr(body)(k))
      }

    case lifted.Try(body, handler) =>
      val handlers: List[ml.Expr.Make] = handler.map {
        case Implementation(interface, ops) =>
          val fields = ops.map {
            case Operation(op, lifted.Block.BlockLit(tparams, params, body)) =>
              // TODO refactor
              val args = params.init.map(paramToML(_))
              val resumeName = name(params.last.id)
              val ev = freshName("ev")
              val evResume = freshName("ev_resume")
              val v = freshName("v")
              val k1 = freshName("k1")
              val k2 = freshName("k2")

              // ev (fn k1 => fn k2 => let fun resume ev_res v = ev_res k1(v); in body[[k2]] end)
              val newBody = ml.Call(
                ml.Expr.Variable(ev)
              )(
                ml.Lambda(
                  ml.Param.Named(k1)
                )(ml.Lambda(
                    ml.Param.Named(k2)
                  )(ml.mkLet(
                    List(ml.Binding.FunBind(
                      resumeName,
                      List(ml.Param.Named(evResume), ml.Param.Named(v)),
                      ml.Call(evResume)(ml.Call(k1)(ml.Expr.Variable(v)))
                    )),
                    toMLExpr(body)(ml.Variable(k2)))
                  )
                )
              )
              ml.Expr.Lambda(ml.Param.Named(ev) :: args, newBody)
          }
          ml.Expr.Make(name(interface.name), expsToTupleIsh(fields))
      }
      val args = ml.Consts.lift :: handlers

      CPS.inline { k =>
        ml.Call(CPS.reset(ml.Call(toML(body))(args: _*)), List(k.reify))
      }

    case Region(body) =>
      CPS.inline { k => ml.Call(ml.Call(ml.Consts.withRegion)(toML(body)), List(k.reify)) }
  }

  def createBinder(id: Symbol, binding: Expr)(using Context): Binding = {
    ml.ValBind(name(id), toML(binding))
  }

  def createBinder(id: Symbol, binding: Block)(using Context): Binding = {
    binding match {
      case BlockLit(tparams, params, body) =>
        val k = freshName("k")
        ml.FunBind(name(id), params.map(p => ml.Param.Named(toML(p))) :+ ml.Param.Named(k), toMLExpr(body)(ml.Variable(k)))
      case _ =>
        ml.ValBind(name(id), toML(binding))
    }
  }

  def toML(defn: Definition)(using C: Context): ml.Binding = defn match {
    case Definition.Def(id, block) => createBinder(id, block)
    case Definition.Let(Wildcard(), binding) => ml.Binding.AnonBind(toML(binding))
    case Definition.Let(id, binding) => createBinder(id, binding)
  }

  def toML(block: BlockLit)(using Context): ml.Lambda = block match {
    case BlockLit(tparams, params, body) =>
      val k = freshName("k")
      ml.Lambda(params.map(paramToML(_)) :+ ml.Param.Named(k), toMLExpr(body)(ml.Variable(k)))
  }

  def toML(block: Block)(using C: Context): ml.Expr = block match {
    case lifted.BlockVar(id, _) =>
      Variable(name(id))

    case b @ lifted.BlockLit(_, _, _) =>
      toML(b)

    case lifted.Member(b, field, annotatedType) =>
      val selector = field match {
        case op: symbols.Operation =>
          dataSelectorName(op.interface, op)
        case f: symbols.Field => fieldSelectorName(f)
        case _: symbols.TermSymbol => C.panic("TermSymbol Member is not supported")
      }
      ml.Call(selector)(toML(b))

    case lifted.Unbox(e) => toML(e) // not sound

    case lifted.New(Implementation(interface, operations)) =>
      ml.Expr.Make(name(interface.name), expsToTupleIsh(operations map toML))
  }

  def toML(op: Operation)(using Context): ml.Expr = {
    val Operation(_, implementation) = op
    toML(implementation)
  }

  def toML(scope: Evidence): ml.Expr = scope match {
    case Evidence(Nil) => Consts.here
    case Evidence(ev :: Nil) => Variable(name(ev))
    case Evidence(scopes) =>
      scopes.map(s => ml.Variable(name(s))).reduce(ml.Call(Consts.nested)(_, _))
  }

  def toML(expr: Expr)(using C: Context): ml.Expr = expr match {
    case l: Literal =>
      def numberString(x: AnyVal): ml.Expr = {
        val s = x.toString
        if (s.startsWith("-")) {
          ml.RawExpr(s"~${s.substring(1)}")
        } else ml.RawValue(s)
      }

      l.value match {
        case v: Byte => numberString(v)
        case v: Short => numberString(v)
        case v: Int => numberString(v)
        case v: Long => numberString(v)
        case v: Float => numberString(v)
        case v: Double => numberString(v)
        case _: Unit => Consts.unitVal
        case v: String => MLString(v)
        case v: Boolean => if (v) Consts.trueVal else Consts.falseVal
        case _ => ml.RawValue(l.value.toString)
      }
    case ValueVar(id, _) => ml.Variable(name(id))

    case PureApp(b, _, args) =>
      val mlArgs = args map {
        case e: Expr => toML(e)
        case b: Block => toML(b)
        case e: Evidence => toML(e)
      }
      b match {
        case BlockVar(id@symbols.Constructor(_, _, _, symbols.TypeConstructor.DataType(_, _, _)), _) =>
          ml.Expr.Make(name(id), expsToTupleIsh(mlArgs))
        case BlockVar(id@symbols.Constructor(_, _, _, symbols.TypeConstructor.Record(_, _, _)), _) =>
          ml.Expr.Make(name(id), expsToTupleIsh(mlArgs))
        case _ => ml.Call(toML(b), mlArgs)
      }

    case Select(b, field, _) =>
      ml.Call(fieldSelectorName(field))(toML(b))

    case Run(s) => toMLExpr(s).run

    case Box(b) => toML(b) // not sound
  }

  enum Continuation {
    case Dynamic(cont: ml.Expr)
    case Static(cont: ml.Expr => ml.Expr)

    def apply(e: ml.Expr): ml.Expr = this match {
      case Continuation.Dynamic(k) => ml.Call(k)(e)
      case Continuation.Static(k) => k(e)
    }
    def reify: ml.Expr = this match {
      case Continuation.Dynamic(k) => k
      case Continuation.Static(k) =>
        val a = freshName("a")
        ml.Lambda(ml.Param.Named(a))(k(ml.Variable(a)))
    }
    def reflect: ml.Expr => ml.Expr = this match {
      case Continuation.Static(k) => k
      case Continuation.Dynamic(k) => a => ml.Call(k)(a)
    }
  }

  class CPS(prog: Continuation => ml.Expr) {
    def apply(k: Continuation): ml.Expr = prog(k)
    def apply(k: ml.Expr): ml.Expr = prog(Continuation.Dynamic(k))
    def apply(k: ml.Expr => ml.Expr): ml.Expr = prog(Continuation.Static(k))

    def flatMap(f: ml.Expr => CPS): CPS = CPS.inline(k => prog(Continuation.Static(a => f(a)(k))))
    def map(f: ml.Expr => ml.Expr): CPS = flatMap(a => CPS.pure(f(a)))
    def run: ml.Expr = prog(Continuation.Static(a => a))
  }

  object CPS {

    def inline(prog: Continuation => ml.Expr): CPS = CPS(prog)
    def join(prog: Continuation => ml.Expr): CPS = CPS {
      case k: Continuation.Dynamic => prog(k)
      case k: Continuation.Static =>
        val kName = freshName("k")
        mkLet(List(ValBind(kName, k.reify)), prog(Continuation.Dynamic(ml.Variable(kName))))
    }

    def reset(prog: ml.Expr): ml.Expr =
      val a = freshName("a")
      val k2 = freshName("k2")
      // fn a => fn k2 => k2(a)
      val pure = ml.Lambda(ml.Param.Named(a)) { ml.Lambda(ml.Param.Named(k2)) { ml.Call(ml.Variable(k2), List(ml.Variable(a))) }}
      ml.Call(prog, List(pure))

    def pure(expr: ml.Expr): CPS = CPS.inline(k => k(expr))

    def runMain(main: MLName): ml.Expr = ml.Call(main)(id, id)

    def id =
      val a = MLName("a")
      ml.Lambda(ml.Param.Named(a))(ml.Variable(a))
  }

  def typesToTupleIsh(types: List[ml.Type]): Option[ml.Type] = types match {
    case Nil => None
    case one :: Nil => Some(one)
    case fieldTypes => Some(ml.Type.Tuple(fieldTypes))
  }

  def expsToTupleIsh(exps: List[ml.Expr]): Option[ml.Expr] = exps match {
    case Nil => None
    case one :: Nil => Some(one)
    case exps => Some(ml.Expr.Tuple(exps))
  }

  def fieldSelectorName(f: Symbol)(using C: Context): MLName = f match {
    case f: symbols.Field =>
      dataSelectorName(f.constructor, f)
    case _ => C.panic("Record fields are not actually a field")
  }

  def dataSelectorName(data: Id, selection: Id)(using C: Context): MLName = {
    val dataName = name(data)
    val selectionName = name(selection)
    MLName(dataName.name + selectionName.name)
  }

  def freshName(s: String): MLName =
    MLName(s + Symbol.fresh.next())
}
