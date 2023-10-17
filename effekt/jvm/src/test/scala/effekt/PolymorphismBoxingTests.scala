package effekt

import java.io.File
import effekt.core.{Block, Definition, DirectApp, PolymorphismBoxing, Pure, Run, Stmt}
import effekt.context.Context
import effekt.source.{IdDef, Import, ModuleDecl}
import kiama.{parsing, util}
import effekt.symbols.{Module, Name, TypeConstructor, TypeSymbol, ValueSymbol, ValueType}
import effekt.source
import effekt.util.messages
import effekt.util.messages.DebugMessaging
import kiama.parsing.{Failure, NoSuccess, Success}
import kiama.util.Severities

abstract class AbstractPolymorphismBoxingTests extends munit.FunSuite {

  def boxDef(tpe: ValueType.ValueTypeApp): List[symbols.Symbol] = {
    val tpeCns: symbols.TypeConstructor.Record =
      symbols.TypeConstructor.Record(symbols.LocalName("Boxed" ++ tpe.constructor.name.name), List(), null)
    tpeCns.constructor =
      symbols.Constructor(symbols.LocalName("MkBoxed" ++ tpe.constructor.name.name), List(), null, tpeCns)
    tpeCns.constructor.fields = List(
      symbols.Field(symbols.LocalName("unbox" ++ tpe.constructor.name.name),
        symbols.ValueParam(symbols.LocalName("value"), Some(tpe)), tpeCns.constructor))
    List(tpeCns, tpeCns.constructor, tpeCns.constructor.fields.head)
  }

  val boxtpes = symbols.builtins.rootTypes.values.flatMap {
    case t: TypeConstructor.ExternType => boxDef(ValueType.ValueTypeApp(t, List()))
    case _ => Nil
  }.map { c => (c.name.name, c) }.toMap

  /** Mock context for Polymorphism boxing.
   * Only implements what is actually used by [[core.PolymorphismBoxing]]
   */
  object context extends Context(new util.Positions()) {
    this.module = new Module(ModuleDecl("test", List(Import("effekt")), List()), util.StringSource("", "test")) {
      override def findPrelude: Module = new Module(ModuleDecl("effekt", List(), List()), util.StringSource("", "effekt")) {
        override def types: Map[String, TypeSymbol] = boxtpes.collect[String, symbols.TypeSymbol]{
          case (k,t: symbols.TypeSymbol) => (k,t)
        }
      }
    }

    object messaging extends DebugMessaging

    def contentsOf(path: String): Option[String] = None

    def findSource(path: String): Option[kiama.util.Source] = None
  }

  val names = new core.Names(boxtpes ++
    symbols.builtins.rootTypes ++ Map(
    // TODO maybe add used names
  ))

  class Renamer(names: core.Names, prefix: String = "l") extends core.Tree.Rewrite {
    var bound: List[symbols.Symbol] = Nil
    def withBindings[R](ids: List[symbols.Symbol])( f: => R ): R = {
      val oldBound = bound
      bound = ids ++ bound
      val res = f
      bound = oldBound
      res
    }
    def withBinding[R](id: symbols.Symbol)( f: => R ): R = withBindings(List(id))(f)

    override def id: PartialFunction[core.Id, core.Id] = { id =>
      if (bound.contains(id)) {
        names.idFor(prefix ++ (bound.length - bound.indexOf(id)).toString)
      } else id
    }

    override def stmt: PartialFunction[Stmt, Stmt] = {
      case core.Scope(definitions, rest) => withBindings(definitions.map{
          case core.Definition.Def(id, _) => id
          case core.Definition.Let(id, _) => id
        }){
        core.Scope(definitions map rewrite, rewrite(rest))
      }
      case core.Val(id, binding, body) => withBinding(id){
        core.Val(rewrite(id), rewrite(binding), rewrite(body))
      }
      case core.Alloc(id, init, reg, body) => withBinding(id){
        core.Alloc(rewrite(id), rewrite(init), rewrite(reg), rewrite(body))
      }
    }
    override def block: PartialFunction[Block, Block] = {
      case Block.BlockLit(tparams, cparams, vparams, bparams, body) =>
        withBindings(cparams ++ vparams.map(_.id) ++ bparams.map(_.id)){
          Block.BlockLit(tparams, cparams map rewrite, vparams map rewrite, bparams map rewrite,
            rewrite(body))
        }
    }

    def apply(m: core.ModuleDecl): core.ModuleDecl = m match {
      case core.ModuleDecl(path, imports, declarations, externs, definitions, exports) =>
        core.ModuleDecl(path, imports, declarations, externs, definitions map rewrite, exports)
    }
  }

  def assertTransformsTo(input: String, expected: String): Unit = {
    val pInput = core.CoreParsers.module(input, names) match {
      case Success(result, next) => result
      case nosuccess: NoSuccess => fail(nosuccess.toMessage)
    }
    val pExpected = core.CoreParsers.module(expected, names) match {
      case Success(result, next) => result
      case nosuccess: NoSuccess => fail(nosuccess.toMessage)
    }
    given core.PolymorphismBoxing.PContext = new PolymorphismBoxing.PContext(List())(using context)
    val got = PolymorphismBoxing.transform(pInput)
    val renamer: Renamer = Renamer(names)
    assertEquals(renamer(got), renamer(pExpected))
  }
}
class PolymorphismBoxingTests extends AbstractPolymorphismBoxingTests {

  test("simple non-polymorphic code should stay the same"){
    val code =
      """module main
        |
        |extern {} def bar() {f@f: MyInterface}: Foo at {f} = "my decl"
        |extern {} def bar() {g: MyInterface}: Foo at {g} = "my decl"
        |
        |def prim = { () => return 42 }
        |def union = { () => if (true) (f: () => Int @ {f})() else (g: () => Unit @ {g})() }
        |def main = { () => return () }
        |
        |export prim
        |""".stripMargin
    assertTransformsTo(code, code)
  }

  test("if only data types are used, code should stay the same"){
    val code =
      """module main
        |
        |type Foo { X() B() }
        |
        |def baz = { ['b](){ f@f: MyInterface } => (f: () => Unit @ {})() }
        |def bar = { ['b](){ f@f: MyInterface } => return box {f} (f : MyInterface @ {f}) }
        |def id = { ['A](a: 'A) => return a: 'A }
        |
        |def returnA = { () => return (id: ['A]('A) => 'A @ {})[Foo]((X: () => Foo @ {})()) }
        |def pReturnA = { ['B](b: 'B) => return (id: ['A]('A) => 'A @ {})['A](b: 'B) }
        |
        |""".stripMargin
    assertTransformsTo(code,code)
  }

  test("simple PureApp with int gets wrapped"){
    val from =
      """module main
        |
        |def id = { ['A](a: 'A) => return a: 'A }
        |def idInt = { (x: Int) => return (id: ['A]('A) => 'A @ {})[Int](x: Int) }
        |""".stripMargin
    val to =
      """module main
        |
        |def id = { ['A](a: 'A) => return a: 'A }
        |def idInt = { (x: Int) => return (id: ['A]('A) => 'A @ {})[BoxedInt]((MkBoxedInt: (Int) => BoxedInt @ {})(x: Int)).unboxInt: Int }
        |""".stripMargin
    assertTransformsTo(from, to)
  }

  test("simple App with int gets wrapped"){
    val from =
      """module main
        |
        |def id = { ['A](a: 'A) => return a: 'A }
        |def idInt = { (x: Int) => (id: ['A]('A) => 'A @ {})[Int](x: Int) }
        |""".stripMargin
    val to =
      """module main
        |
        |def id = { ['A](a: 'A) => return a: 'A }
        |def idInt = { (x: Int) => val tmp = (id: ['A]('A) => 'A @ {})[BoxedInt]((MkBoxedInt: (Int) => BoxedInt @ {})(x: Int)) ; return tmp:BoxedInt.unboxInt: Int }
        |""".stripMargin
    assertTransformsTo(from, to)
  }

  test("DirectApp with [Int] gets wrapped correctly"){
    val from =
      """module main
        |
        |def id = { ['A](a: 'A) => return a: 'A }
        |def idInt = { (x: Int) =>
        |    {
        |        let res = !(id: ['A]('A) => 'A @ {})[Int](x: Int)
        |        return res: Int
        |    }
        |}
        |""".stripMargin
    val to =
      """module main
        |
        |def id = { ['A](a: 'A) => return a: 'A }
        |def idInt = { (x: Int) =>
        |    {
        |        let res = run {
        |            let boxedRes = !(id: ['A]('A) => 'A @ {})[BoxedInt]((MkBoxedInt: (Int) => BoxedInt @ {})(x: Int))
        |            return boxedRes:BoxedInt.unboxInt: Int
        |        }
        |        return res: Int
        |    }
        |}
        |""".stripMargin
    assertTransformsTo(from,to)
  }

  test("block parameters get wrapped \"inversely\""){
    val from =
      """module main
        |def test = { () =>
        |    (hof: ['A](){ b : ('A) => 'A } => 'A @ {} )[Int](){ (x: Int) => return x: Int }
        |}
        |""".stripMargin
    val to =
      """module main
        |def test = { () =>
        |    val r = (hof: ['A](){ b : ('A) => 'A } => 'A @ {} )[BoxedInt](){ (boxedX: BoxedInt) =>
        |      {
        |         def originalFn = { (x: Int) => return x: Int }
        |         val result = (originalFn: (Int) => Int @ {})(boxedX: BoxedInt.unboxInt: Int);
        |         return (MkBoxedInt: (Int) => BoxedInt @ {})(result: Int)
        |      }
        |    };
        |    return r:BoxedInt.unboxInt: Int
        |}
        |""".stripMargin
    assertTransformsTo(from, to)
  }

  test("higher higher order functions get wrapped correctly"){
    val from =
      """module main
        |
        |def hhof_caller = { () =>
        |    (hhof: ['A](){ b: (){ hhofarg: ('A) => 'A } => 'A } => 'A @ {})[Int](){
        |        (){ hhofarg: (Int) => Int } => (hhofarg: (Int) => Int @ {})(5)
        |    }
        |}
        |""".stripMargin
    val to =
      """module main
        |
        |def hhof_caller = { () =>
        |    val result = (hhof: ['A](){ b: (){ hhofarg: ('A) => 'A } => 'A } => 'A @ {})[BoxedInt](){
        |        (){ hhofargB: ('A) => 'A } =>
        |          {
        |              def originalFn = { (){ hhofarg: (Int) => Int } => (hhofarg: (Int) => Int @ {})(5) }
        |              val res = (originalFn: (){ hhofarg: (Int) => Int } => Int @ {})(){
        |                  (hhofargarg: Int) =>
        |                      {
        |                        def tmp = hhofargB: ('A) => 'A @ {}
        |                        val rres = (tmp: ('A) => 'A @ {})((MkBoxedInt: (Int) => BoxedInt @ {})(hhofargarg: Int));
        |                        return rres:BoxedInt.unboxInt: Int
        |                      }
        |              };
        |              return (MkBoxedInt: (Int) => BoxedInt @ {})(res:Int)
        |          }
        |    };
        |    return result:BoxedInt.unboxInt: Int
        |}
        |""".stripMargin
    assertTransformsTo(from, to)
  }
}
