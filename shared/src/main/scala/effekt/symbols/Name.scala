package effekt.symbols

import effekt.source.Id
import effekt.context.Context

trait Name {
  /**
   * The local part of the name relative to its potential parent.
   *
   * @example The local part of the name `"foo.baz.bar"` is `"bar"`.
   */
  val localName: String

  /**
   * Computes the qualified version of this name.
   * @return A String containing the local part of this name prefixed by their parent name. Components are separated by the point character (`.`).
   *
   * @note this should only be used for reporting errors, not for code generation.
   * @note Note to the previous note: Is this still true for the new module-system?
   */
  def qualifiedName: String = this match {
    case ToplevelName(name)       => name
    case NestedName(parent, name) => s"${parent.qualifiedName}.$localName"
  }

  /**
   * @return The parent of this name or `None` if this name represents a top-level name.
   */
  def parentOption: Option[Name] = this match {
    case ToplevelName(_)       => None
    case NestedName(parent, _) => Some(parent)
  }

  /**
   * @return The qualified name of the parent or `None` if this name doesn't have a parent.
   */
  def qualifiedParentOption: Option[String] = parentOption.map((parent) => parent.qualifiedName)

  /**
   * Creates a new name with the same parent as this name and the changed local name.
   * @param f rename function.
   * @return A Name with local name euqal to `f(this.localName)`.
   */
  def rename(f: String => String): Name = this match {
    case ToplevelName(name)  => ToplevelName(f(name))
    case NestedName(p, name) => NestedName(p, f(name))
  }

  /**
   * Creates a new `NestedName` with this name as the parent.
   * @param name the local name of the nested name.
   * @return A `NestedName` with this Name as parent.
   */
  def nested(name: String) = NestedName(this, name)

  override def toString = localName
}

/**
 * A Name without a parent, e.g. the name of a global symbol.
 * @param localName the local name which is also the qualified name.
 */
case class ToplevelName(localName: String) extends Name

/**
 * A Name which is a child of a other name, e.g. the name of a nested module.
 *
 * Creation of `NestedName` via the [[Name.nested()]] method is prefered.
 *
 * @param parent The parent of this name.
 * @param localName The local name relative to the parent.
 */
case class NestedName(parent: Name, localName: String) extends Name

// Pseudo-constructors to safely convert ids and strings into names.
object Name {
  /**
   * Creates a name for the ID inside the module of the current context.
   *
   * @param id The id to be converted into a name.
   * @param C The implicit context used to find the current module.
   * @return A qualified name inside the module.
   */
  def apply(id: Id)(implicit C: Context): Name = Name.apply(id.name, C.module)

  /**
   * Creates a qualified name for a symbol inside the given module.
   *
   * @param name the local name of the symbol.
   * @param module the module containing the symbol.
   * @return A name with the module path as a parent.
   *
   * @example The name `"baz"` inside the module with the path `"foo/bar"` is parsed as qualified name `"foo.bar.baz"`
   */
  def apply(name: String, module: Module): Name = {
    val moduleName = Name(module.path.replace("/", "."))
    moduleName.nested(name)
  }

  /**
   * Parses a [[Name]] from a String which might be a qualified name.
   * @param name A non-empty String which might be a qualified or local name.
   * @return A Name where [[Name.qualifiedName]] is equal to the input name.
   *
   * @example `Name("foo") == ToplevelName("foo")`
   * @example `Name("foo.bar.baz") == NestedName(NestedName(ToplevelName("foo"), "bar"), "baz")`
   */
  def apply(name: String): Name = {
    assert(name.nonEmpty, "Name cannot be empty.")

    // Is nested?
    if (name.contains(".")) {
      val segments = name.split(".")
      val top: Name = ToplevelName(segments.head)
      segments.drop(1).foldLeft(top)((parent, segment) => parent.nested(segment))
    } else {
      ToplevelName(name)
    }
  }
}
