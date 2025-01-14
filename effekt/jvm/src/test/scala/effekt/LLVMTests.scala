package effekt

import java.io.File
import sbt.io._
import sbt.io.syntax._
import scala.language.implicitConversions
import scala.sys.process.Process

class LLVMTests extends EffektTests {

  def backendName = "llvm"

  override def valgrind = sys.env.get("EFFEKT_VALGRIND").nonEmpty
  override def debug = sys.env.get("EFFEKT_DEBUG").nonEmpty

  override lazy val positives: List[File] = List(
    examplesDir / "llvm",
    examplesDir / "pos",
    examplesDir / "benchmarks",
  )

  lazy val bugs: List[File] = List(
    // Jump to the invalid address stated on the next line
    examplesDir / "benchmarks" / "input_output" / "dyck_one.effekt",
    examplesDir / "benchmarks" / "input_output" / "number_matrix.effekt",
    examplesDir / "benchmarks" / "input_output" / "word_count_ascii.effekt",
    examplesDir / "benchmarks" / "input_output" / "word_count_utf8.effekt",
  )

  /**
   * Documentation of currently failing tests and their reason
   */
  lazy val missingFeatures: List[File] = List(

    // now show instance for records / datatypes
    examplesDir / "pos" / "builtins.effekt",
    examplesDir / "pos" / "namespaces.effekt", // also leaks
    examplesDir / "pos" / "triples.effekt",
    examplesDir / "pos" / "either.effekt",

    // inspect
    examplesDir / "pos" / "probabilistic.effekt",
    examplesDir / "pos" / "nim.effekt",
    examplesDir / "pos" / "exists.effekt",

    // arrays
    examplesDir / "pos" / "raytracer.effekt",
    examplesDir / "pos" / "issue319.effekt",

    // Regex
    examplesDir / "pos" / "simpleparser.effekt",

    // toplevel def and let bindings
    examplesDir / "pos" / "capture" / "mbed.effekt",

    // unsafe cont
    examplesDir / "pos" / "propagators.effekt",

    // Only JS (tests should be moved to a JS folder)
    examplesDir / "pos" / "genericcompare.effekt",
    examplesDir / "pos" / "multiline_extern_definition.effekt",
    examplesDir / "pos" / "maps.effekt",
    examplesDir / "pos" / "capture" / "resources.effekt",
    examplesDir / "pos" / "io",

    // higher order foreign functions are not supported
    examplesDir / "pos" / "capture" / "ffi_blocks.effekt",

    // See PR #355
    examplesDir / "llvm" / "string_toint.effekt",

    // Generic equality
    examplesDir / "pos" / "issue429.effekt",

    // Generic comparison
    examplesDir / "pos" / "issue733.effekt",
  )

  override lazy val ignored: List[File] = bugs ++ missingFeatures
}
