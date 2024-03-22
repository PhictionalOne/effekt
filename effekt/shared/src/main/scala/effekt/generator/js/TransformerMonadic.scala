package effekt
package generator
package js

import effekt.context.Context
import effekt.context.assertions.*
import effekt.core.{given, *}
import effekt.util.paths.*
import effekt.{ Compiled, CoreTransformed, symbols }
import effekt.symbols.{ Symbol, Module, Wildcard }

import kiama.output.ParenPrettyPrinter
import kiama.output.PrettyPrinterTypes.Document
import kiama.util.Source

import scala.language.implicitConversions

object TransformerMonadic extends Transformer {

  // return BODY.run()
  def run(body: js.Expr): js.Stmt =
    js.Return(js.Call(js.Member(body, JSName("run")), Nil))

  def transformModule(module: core.ModuleDecl, imports: List[js.Import], exports: List[js.Export])(using DeclarationContext, Context): js.Module =
    toJS(module, imports, exports)

  def toJS(p: Param): JSName = nameDef(p.id)

  def toJS(e: core.Extern)(using DeclarationContext, Context): js.Stmt = e match {
    case Extern.Def(id, tps, cps, vps, bps, ret, capt, body) =>
      js.Function(nameDef(id), (vps ++ bps) map toJS, List(js.Return(toJS(body))))

    case Extern.Include(contents) =>
      js.RawStmt(contents)
  }

  def toJS(t: Template[Pure])(using DeclarationContext, Context): js.Expr =
    js.RawExpr(t.strings, t.args.map(toJS))

  def toJS(b: core.Block)(using DeclarationContext, Context): js.Expr = b match {
    case BlockVar(v, _, _) =>
      nameRef(v)
    case BlockLit(tps, cps, vps, bps, body) =>
      val (stmts, ret) = toJSStmt(body)
      monadic.Lambda((vps ++ bps) map toJS, stmts, ret) // TODO
    case Member(b, id, tpe) =>
      js.Member(toJS(b), memberNameRef(id))
    case Unbox(e)     => toJS(e)
    case New(handler) => toJS(handler)
  }

  // TODO this could be done in core.Tree
  def inlineExtern(args: List[Pure], params: List[ValueParam], template: Template[Pure])(using D: DeclarationContext, C: Context): js.RawExpr =
      import core.substitutions.*
      val valueSubstitutions = (params zip args).map { case (param, pure) => param.id -> pure }.toMap
      given Substitution = Substitution(Map.empty, Map.empty, valueSubstitutions, Map.empty)

      js.RawExpr(template.strings, template.args.map(substitute).map(toJS))

  def toJS(expr: core.Expr)(using D: DeclarationContext, C: Context): js.Expr = expr match {
    case Literal((), _) => $effekt.field("unit")
    case Literal(s: String, _) => JsString(s)
    case literal: Literal => js.RawExpr(literal.value.toString)
    case ValueVar(id, tpe) => nameRef(id)

    case DirectApp(f: core.Block.BlockVar, targs, vargs, Nil) =>
      val extern = D.getExternDef(f.id)
      inlineExtern(vargs, extern.vparams, extern.body)

    case DirectApp(b, targs, vargs, bargs) =>
      js.Call(toJS(b), vargs.map(toJS) ++ bargs.map(toJS))

    case PureApp(f: core.Block.BlockVar, targs, vargs) =>
      val extern = D.getExternDef(f.id)
      inlineExtern(vargs, extern.vparams, extern.body)

    case PureApp(b, targs, args) => C.panic("Should have been inlined")
    case Make(data, tag, vargs) => js.New(nameRef(tag), vargs map toJS)
    case Select(target, field, _) => js.Member(toJS(target), memberNameRef(field))
    case Box(b, _) => toJS(b)
    case Run(s) => monadic.Run(toJSMonadic(s))
  }

  def toJS(handler: core.Implementation)(using DeclarationContext, Context): js.Expr =
    js.Object(handler.operations.map {
      case Operation(id, tps, cps, vps, bps, resume, body) =>
        val (stmts, ret) = toJSStmt(body)
        nameDef(id) -> monadic.Lambda((vps ++ bps ++ resume.toList) map toJS, stmts, ret)
    })

  def toJS(handler: core.Implementation, prompt: js.Expr)(using DeclarationContext, Context): js.Expr =
    js.Object(
      handler.operations.map {
        // (args...cap...) => $effekt.shift(prompt, (resume) => { ... body ... resume((cap...) => { ... }) ... })
        case Operation(id, tps, cps, vps, bps,
            Some(BlockParam(resume, core.BlockType.Function(_, _, _, List(core.BlockType.Function(_, _, _, bidirectionalTpes, _)), _), _)),
            body) =>
          // add parameters for bidirectional arguments
          val biParams = bidirectionalTpes.map { _ => freshName("cap") }
          val biArgs   = biParams.map { p => js.Variable(p) }
          val thunk    = freshName("thunk")
          val force    = monadic.Call(js.Variable(thunk), biArgs)

          val (stmts, ret) = toJSStmt(body)
          val lambda = monadic.Lambda((vps ++ bps).map(toJS) ++ biParams, Nil,
            monadic.Bind(monadic.Builtin("shift", prompt,
              monadic.Lambda(List(nameDef(resume)), stmts, ret)), thunk, force))

          nameDef(id) -> lambda

      // (args...) => $effekt.shift(prompt, (resume) => { ... BODY ... resume(v) ... })
      case Operation(id, tps, cps, vps, bps, Some(resume), body) =>
        val (stmts, ret) = toJSStmt(body)
        val lambda = monadic.Lambda((vps ++ bps) map toJS, Nil,
          monadic.Builtin("shift", prompt,
            monadic.Lambda(List(toJS(resume)), stmts, ret)))

        nameDef(id) -> lambda

      case Operation(id, tps, cps, vps, bps, None, body) => Context.panic("Effect handler should take continuation")
    })

  def toJS(module: core.ModuleDecl, imports: List[js.Import], exports: List[js.Export])(using DeclarationContext, Context): js.Module = {
    val name    = JSName(jsModuleName(module.path))
    val externs = module.externs.collect {
      case d if cannotInline(d) => toJS(d)
    }
    val decls   = module.declarations.flatMap(toJS)
    val stmts   = module.definitions.map(toJS)
    val state   = generateStateAccessors
    js.Module(name, imports, exports, state ++ decls ++ externs ++ stmts)
  }

  /**
   * Exports are used in separate compilation (on the website).
   * We should only export those symbols that we have not inlined away, already.
   */
  override def shouldExport(id: Id)(using D: DeclarationContext): Boolean =
    super.shouldExport(id) && D.findExternDef(id).forall(cannotInline)


  def cannotInline(f: Extern): Boolean = f match {
    case Extern.Def(id, tparams, cparams, vparams, bparams, ret, annotatedCapture, body) =>
      val hasBlockParameters = bparams.nonEmpty
      val isControlEffecting = annotatedCapture contains symbols.builtins.ControlCapability.capture
      hasBlockParameters || isControlEffecting
    case Extern.Include(contents) => true
  }

  /**
   * Translate the statement to a javascript expression in a monadic expression context.
   *
   * Not all statement types can be printed in this context!
   */
  def toJSMonadic(s: core.Stmt)(using DeclarationContext, Context): monadic.Control = s match {
    case Val(Wildcard(), binding, body) =>
      monadic.Bind(toJSMonadic(binding), toJSMonadic(body))

    case Val(id, binding, body) =>
      monadic.Bind(toJSMonadic(binding), nameDef(id), toJSMonadic(body))

    case Var(id, init, cap, body) =>
      val (stmts, ret) = toJSStmt(body)
      monadic.State(nameDef(id), toJS(init), stmts, ret)

    case App(b, targs, vargs, bargs) =>
      monadic.Call(toJS(b), vargs.map(toJS) ++ bargs.map(toJS))

    case Get(id, capt, tpe) => Context.panic("Should have been translated to direct style")
    case Put(id, capt, value) =>  Context.panic("Should have been translated to direct style")

    case If(cond, thn, els) =>
      monadic.If(toJS(cond), toJSMonadic(thn), toJSMonadic(els))

    case Return(e) =>
      monadic.Pure(toJS(e))

    // $effekt.handle(p => {
    //   const amb = { flip: ... };
    //
    // })
    case Try(core.BlockLit(_, _, _, bps, body), hs) =>
      val prompt = freshName("p")

      val handlerDefs = (bps zip hs).map {
        case (param, handler) => js.Const(toJS(param), toJS(handler, js.Variable(prompt)))
      }
      val (stmts, ret) = toJSStmt(body)

      monadic.Handle(monadic.Lambda(List(prompt), handlerDefs ++ stmts, ret))

    case Try(_, _) =>
      Context.panic("Body of the try is expected to be a block literal in core.")

    case Region(body) =>
      monadic.Builtin("withRegion", toJS(body))

    case Hole() =>
      monadic.Builtin("hole")

    case other => toJSStmt(other) match {
      case (Nil, ret) => ret
      case (stmts, ret) => monadic.Call(monadic.Lambda(Nil, stmts, ret), Nil)
    }
  }

  def toJS(d: core.Declaration)(using Context): List[js.Stmt] = d match {
    case Data(did, tparams, ctors) =>
      ctors.zipWithIndex.map { case (ctor, index) => generateConstructor(ctor, index) }

    // interfaces are structurally typed at the moment, no need to generate anything.
    case Interface(id, tparams, operations) =>
      Nil
  }

  def toJS(d: core.Definition)(using DeclarationContext, Context): js.Stmt = d match {
    case Definition.Def(id, BlockLit(tps, cps, vps, bps, body)) =>
      val (stmts, jsBody) = toJSStmt(body)
      monadic.Function(nameDef(id), (vps++ bps) map toJS, stmts, jsBody)

    case Definition.Def(id, block) =>
      js.Const(nameDef(id), toJS(block))

    case Definition.Let(Wildcard(), binding) =>
      js.ExprStmt(toJS(binding))

    case Definition.Let(id, binding) =>
      js.Const(nameDef(id), toJS(binding))
  }

  /**
   * Translate the statement in a js "direct-style" statement context.
   *
   * That is, multiple statements that end in one monadic return
   */
  def toJSStmt(s: core.Stmt)(using D: DeclarationContext, C: Context): (List[js.Stmt], monadic.Control) = s match {
    case Scope(definitions, body) =>
      val (stmts, ret) = toJSStmt(body)
      (definitions.map(toJS) ++ stmts, ret)

    case Alloc(id, init, region, body) if region == symbols.builtins.globalRegion =>
      val (stmts, ret) = toJSStmt(body)
      (js.Const(nameDef(id), $effekt.call("ref", toJS(init))) :: stmts, ret)

    case Alloc(id, init, region, body) =>
      val (stmts, ret) = toJSStmt(body)
      (js.Const(nameDef(id), js.MethodCall(nameRef(region), `fresh`, toJS(init))) :: stmts, ret)

    // (function () { switch (sc.tag) {  case 0: return f17.apply(null, sc.data) }
    case Match(sc, clauses, default) =>
      val scrutinee = toJS(sc)

      val sw = js.Switch(js.Member(scrutinee, `tag`), clauses map {
        // f17.apply(null, sc.__data)
        case (c, block@BlockLit(tparams, cparams, vparams, bparams, body)) =>
          val fields = D.getConstructor(c).fields.map(_.id)
          val freeVars = core.Variables.free(body).map(_.id)

          val params = vparams.map { p => p.id }
          def isUsed(x: Id) = freeVars contains x

          val extractedFields = (params zip fields).collect { case (p, f) if isUsed(p) =>
            js.Const(nameDef(p), js.Member(scrutinee, memberNameRef(f)))
          }

          val (stmts, ret) = toJSStmt(body)

          (tagFor(c), extractedFields ++ stmts ++ List(js.Return(monadic.asExpr(ret))))
      }, None)

      val (stmts, ret) = default.map(toJSStmt).getOrElse((Nil, monadic.Pure(js"null")))
      (sw :: stmts, ret)


    case other =>
      (Nil, toJSMonadic(other))
  }

}
