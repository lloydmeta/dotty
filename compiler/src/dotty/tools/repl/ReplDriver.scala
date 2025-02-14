package dotty.tools.repl

import java.io.PrintStream

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.NameKinds.SimpleNameKind
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols.{Symbol, defn}
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.printing.SyntaxHighlighting
import dotty.tools.dotc.reporting.MessageRendering
import dotty.tools.dotc.reporting.diagnostic.{Message, MessageContainer}
import dotty.tools.dotc.util.Positions.Position
import dotty.tools.dotc.util.{SourceFile, SourcePosition}
import dotty.tools.dotc.{CompilationUnit, Driver}
import dotty.tools.io._
import org.jline.reader._

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/** The state of the REPL contains necessary bindings instead of having to have
 *  mutation
 *
 *  The compiler in the REPL needs to do some wrapping in order to compile
 *  valid code. This wrapping occurs when a single `MemberDef` that cannot be
 *  top-level needs to be compiled. In order to do this, we need some unique
 *  identifier for each of these wrappers. That identifier is `objectIndex`.
 *
 *  Free expressions such as `1 + 1` needs to have an assignment in order to be
 *  of use. These expressions are therefore given a identifier on the format
 *  `resX` where `X` starts at 0 and each new expression that needs an
 *  identifier is given the increment of the old identifier. This identifier is
 *  `valIndex`.
 *
 *  @param objectIndex the index of the next wrapper
 *  @param valIndex    the index of next value binding for free expressions
 *  @param imports     the list of user defined imports
 *  @param context     the latest compiler context
 */
case class State(objectIndex: Int,
                 valIndex: Int,
                 imports: List[untpd.Import],
                 context: Context)

/** Main REPL instance, orchestrating input, compilation and presentation */
class ReplDriver(settings: Array[String],
                 out: PrintStream = Console.out,
                 classLoader: Option[ClassLoader] = None) extends Driver {

  /** Overridden to `false` in order to not have to give sources on the
   *  commandline
   */
  override def sourcesRequired = false

  /** Create a fresh and initialized context with IDE mode enabled */
  private[this] def initialCtx = {
    val rootCtx = initCtx.fresh.addMode(Mode.ReadPositions).addMode(Mode.Interactive)
    val ictx = setup(settings, rootCtx)._2
    ictx.base.initialize()(ictx)
    ictx
  }

  /** the initial, empty state of the REPL session */
  protected[this] def initState = State(0, 0, Nil, rootCtx)

  /** Reset state of repl to the initial state
   *
   *  This method is responsible for performing an all encompassing reset. As
   *  such, when the user enters `:reset` this method should be called to reset
   *  everything properly
   */
  protected[this] def resetToInitial(): Unit = {
    rootCtx = initialCtx
    if (rootCtx.settings.outputDir.isDefault(rootCtx))
      rootCtx = rootCtx.fresh
        .setSetting(rootCtx.settings.outputDir, new VirtualDirectory("<REPL compilation output>"))
    compiler = new ReplCompiler
    rendering = new Rendering(classLoader)
  }

  private[this] var rootCtx: Context = _
  private[this] var compiler: ReplCompiler = _
  private[this] var rendering: Rendering = _

  // initialize the REPL session as part of the constructor so that once `run`
  // is called, we're in business
  resetToInitial()

  /** Run REPL with `state` until `:quit` command found
   *
   *  This method is the main entry point into the REPL. Its effects are not
   *  observable outside of the CLI, for this reason, most helper methods are
   *  `protected final` to facilitate testing.
   */
  final def runUntilQuit(): State = {
    val terminal = new JLineTerminal()

    /** Blockingly read a line, getting back a parse result */
    def readLine(state: State): ParseResult = {
      val completer: Completer = { (_, line, candidates) =>
        val comps = completions(line.cursor, line.line, state)
        candidates.addAll(comps.asJava)
      }
      implicit val ctx = state.context
      try {
        val line = terminal.readLine(completer)
        ParseResult(line)
      }
      catch {
        case _: EndOfFileException => // Ctrl+D
          Quit
      }
    }

    @tailrec def loop(state: State): State = {
      val res = readLine(state)
      if (res == Quit) state
      else loop(interpret(res)(state))
    }

    try withRedirectedOutput { loop(initState) }
    finally terminal.close()
  }

  final def run(input: String)(implicit state: State): State = withRedirectedOutput {
    val parsed = ParseResult(input)(state.context)
    interpret(parsed)
  }

  private def withRedirectedOutput(op: => State): State =
    Console.withOut(out) { Console.withErr(out) { op } }

  private def newRun(state: State) = {
    val run = compiler.newRun(rootCtx.fresh.setReporter(newStoreReporter), state.objectIndex)
    state.copy(context = run.runContext)
  }

  /** Extract possible completions at the index of `cursor` in `expr` */
  protected[this] final def completions(cursor: Int, expr: String, state0: State): List[Candidate] = {
    def makeCandidate(completion: Symbol)(implicit ctx: Context) = {
      val displ = completion.name.toString
      new Candidate(
        /* value    = */ displ,
        /* displ    = */ displ, // displayed value
        /* group    = */ null,  // can be used to group completions together
        /* descr    = */ null,  // TODO use for documentation?
        /* suffix   = */ null,
        /* key      = */ null,
        /* complete = */ false  // if true adds space when completing
      )
    }
    implicit val state = newRun(state0)
    compiler
      .typeCheck(expr, errorsAllowed = true)
      .map { tree =>
        val file = new SourceFile("<completions>", expr)
        val unit = new CompilationUnit(file)
        unit.tpdTree = tree
        implicit val ctx = state.context.fresh.setCompilationUnit(unit)
        val srcPos = SourcePosition(file, Position(cursor))
        val (_, completions) = Interactive.completions(srcPos)
        completions.map(makeCandidate)
      }
      .getOrElse(Nil)
  }

  private def extractImports(trees: List[untpd.Tree]): List[untpd.Import] =
    trees.collect { case imp: untpd.Import => imp }

  private def interpret(res: ParseResult)(implicit state: State): State = {
    val newState = res match {
      case parsed: Parsed if parsed.trees.nonEmpty =>
        compile(parsed, state)

      case SyntaxErrors(_, errs, _) =>
        displayErrors(errs)
        state

      case cmd: Command =>
        interpretCommand(cmd)

      case SigKill => // TODO
        state

      case _ => // new line, empty tree
        state
    }
    out.println()
    newState
  }

  /** Compile `parsed` trees and evolve `state` in accordance */
  private def compile(parsed: Parsed, istate: State): State = {
    def extractNewestWrapper(tree: untpd.Tree): Name = tree match {
      case PackageDef(_, (obj: untpd.ModuleDef) :: Nil) => obj.name.moduleClassName
      case _ => nme.NO_NAME
    }

    implicit val state = newRun(istate)
    compiler
      .compile(parsed)
      .fold(
        displayErrors,
        {
          case (unit: CompilationUnit, newState: State) =>
            val newestWrapper = extractNewestWrapper(unit.untpdTree)
            val newImports = newState.imports ++ extractImports(parsed.trees)
            val newStateWithImports = newState.copy(imports = newImports)

            val warnings = newState.context.reporter.removeBufferedMessages(newState.context)
            displayErrors(warnings)(newState) // display warnings
            displayDefinitions(unit.tpdTree, newestWrapper)(newStateWithImports)
        }
      )
  }

  /** Display definitions from `tree` */
  private def displayDefinitions(tree: tpd.Tree, newestWrapper: Name)(implicit state: State): State = {
    implicit val ctx = state.context

    def resAndUnit(denot: Denotation) = {
      import scala.util.{Success, Try}
      val sym = denot.symbol
      val name = sym.name.show
      val hasValidNumber = Try(name.drop(3).toInt) match {
        case Success(num) => num < state.valIndex
        case _ => false
      }
      name.startsWith(str.REPL_RES_PREFIX) && hasValidNumber && sym.info == defn.UnitType
    }

    def displayMembers(symbol: Symbol) = if (tree.symbol.info.exists) {
      val info = symbol.info
      val defs =
        info.bounds.hi.finalResultType
          .membersBasedOnFlags(Method, Accessor | ParamAccessor | Synthetic | Private)
          .filterNot { denot =>
            denot.symbol.owner == defn.AnyClass ||
            denot.symbol.owner == defn.ObjectClass ||
            denot.symbol.isConstructor
          }

      val vals =
        info.fields
          .filterNot(_.symbol.is(ParamAccessor | Private | Synthetic | Module))
          .filter(_.symbol.name.is(SimpleNameKind))

      val typeAliases =
        info.bounds.hi.typeMembers.filter(_.symbol.info.isInstanceOf[TypeAlias])

      (
        typeAliases.map("// defined alias " + _.symbol.showUser) ++
        defs.map(rendering.renderMethod) ++
        vals.map(rendering.renderVal).flatten
      ).foreach(str => out.println(SyntaxHighlighting(str)))

      state.copy(valIndex = state.valIndex - vals.count(resAndUnit))
    }
    else state

    def isSyntheticCompanion(sym: Symbol) =
      sym.is(Module) && sym.is(Synthetic)

    def displayTypeDefs(sym: Symbol) = sym.info.memberClasses
      .collect {
        case x if !isSyntheticCompanion(x.symbol) && !x.symbol.name.isReplWrapperName =>
          x.symbol
      }
      .foreach { sym =>
        out.println(SyntaxHighlighting("// defined " + sym.showUser))
      }


    ctx.atPhase(ctx.typerPhase.next) { implicit ctx =>

      // Display members of wrapped module:
      tree.symbol.info.memberClasses
        .find(_.symbol.name == newestWrapper.moduleClassName)
        .map { wrapperModule =>
          displayTypeDefs(wrapperModule.symbol)
          displayMembers(wrapperModule.symbol)
        }
        .getOrElse {
          // user defined a trait/class/object, so no module needed
          state
        }
    }
  }

  /** Interpret `cmd` to action and propagate potentially new `state` */
  private def interpretCommand(cmd: Command)(implicit state: State): State = cmd match {
    case UnknownCommand(cmd) =>
      out.println(s"""Unknown command: "$cmd", run ":help" for a list of commands""")
      state

    case Help =>
      out.println(Help.text)
      state

    case Reset =>
      resetToInitial()
      initState

    case Imports =>
      state.imports.foreach(i => out.println(SyntaxHighlighting(i.show(state.context))))
      state

    case Load(path) =>
      val file = new java.io.File(path)
      if (file.exists) {
        val contents = scala.io.Source.fromFile(file).mkString
        run(contents)
      }
      else {
        out.println(s"""Couldn't find file "${file.getCanonicalPath}"""")
        state
      }

    case TypeOf(expr) =>
      compiler.typeOf(expr)(newRun(state)).fold(
        displayErrors,
        res => out.println(SyntaxHighlighting(res))
      )
      state

    case Quit =>
      // end of the world!
      state
  }

  /** A `MessageRenderer` without file positions */
  private val messageRenderer = new MessageRendering {
    override def posStr(pos: SourcePosition, diagnosticLevel: String, message: Message)(implicit ctx: Context): String = ""
  }

  /** Render messages using the `MessageRendering` trait */
  private def renderMessage(cont: MessageContainer): Context => String =
    messageRenderer.messageAndPos(cont.contained(), cont.pos, messageRenderer.diagnosticLevel(cont))(_)

  /** Output errors to `out` */
  private def displayErrors(errs: Seq[MessageContainer])(implicit state: State): State = {
    errs.map(renderMessage(_)(state.context)).foreach(out.println)
    state
  }
}
