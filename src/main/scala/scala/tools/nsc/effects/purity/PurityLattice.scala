package scala.tools.nsc.effects
package purity

import scala.tools.nsc.Global

trait PurityLattice extends EffectLattice {
  val global: Global
  import global._

  type Mod = Locality
  type ResLoc = Locality

  type Effect = PurityEffect

  lazy val top: Effect            = PurityEffect(AnyLoc, AssignAny, AnyLoc)
  lazy val bottom: Effect         = PurityEffect(RefSet(), Assigns(), RefSet())
  lazy val noModAnyResLoc: Effect = PurityEffect(RefSet(), Assigns(), AnyLoc)

  override def effectForPureAnnotated: Effect = noModAnyResLoc


  def join(a: Effect, b: Effect): Effect =
    PurityEffect(a.mod join b.mod, a.assign join b.assign, a.loc join b.loc)

  def meet(a: Effect, b: Effect): Effect =
    PurityEffect(a.mod meet b.mod, a.assign meet b.assign, a.loc meet b.loc)

  def lte(a: Effect, b: Effect): Boolean =
    (a.mod lte b.mod) && (a.assign lte b.assign) && (a.loc lte b.loc)


  case class PurityEffect(mod: Mod, assign: AssignEff, loc: ResLoc) {
    import PurityEffect._
    def toTriple = (mod, assign, loc)
    override def toString() =
      (modToString(mod) ::: assignToString(assign) ::: resLocToString(loc)).mkString(" ")
  }

  object PurityEffect{
    def modToString(mod: Mod) = List(s"@mod(${locToString(mod)})")

    def assignToString(assign: AssignEff, showEmpty: Boolean = false) = assign match {
      case AssignAny => List("@assign(any)")
      case Assigns(as) =>
        if (as.isEmpty && showEmpty) List("@assign()")
        else as.map(p => s"@assign(${p._1.name.toString()},${locToString(p._2)})").toList
    }

    def resLocToString(loc: ResLoc) = List(s"@loc(${locToString(loc)})")

    def locToString(loc: Locality) = loc match {
      case AnyLoc => "any"
      case RefSet(refs) => refsToString(refs)
    }
    
    def refsToString(refs: Set[VarRef]) = {
      refs map {
        case ThisRef(cls) => s"${cls.name.toString()}.this"
        case SymRef(sym) => sym.name.toString()
      } mkString(",")
    }
  }


  sealed trait VarRef
  case class SymRef(sym: Symbol) extends VarRef {
    override def hashCode() = sym.hashCode
    override def equals(other: Any) = other match {
      case SymRef(otherSym) =>
        sym.owner == otherSym.owner && sym.name == otherSym.name
      case _ =>
        false
    }
  }
  case class ThisRef(sym: Symbol) extends VarRef



  // TODO: should factor out the lattice with a top element, something like the below.
  // but failed for now..
/*
  trait ElemOps[T] {
    def join(a: T, b: T): T
    def meet(a: T, b: T): T
    def lte(a: T, b: T): Boolean
  }

  trait TopOrElem[T] {
    implicit def ops: ElemOps[T]

    def join(b: TopOrElem[T]): TopOrElem[T] = (this, b) match {
      case (Top(), _) | (_, Top()) =>
        Top()
      case (e @ Elem(a), Elem(b)) =>
        Elem(ops.join(a, b))
    }

    def meet(b: TopOrElem[T]): TopOrElem[T] = (this, b) match {
      case (Top(), b) => b
      case (a, Top()) => a
      case (e @ Elem(a), Elem(b)) =>
        Elem(ops.meet(a, b))
    }

    def lte(b: TopOrElem[T]): Boolean = (this, b) match {
      case (_, Top()) => true
      case (Top(), _) => false
      case (e @ Elem(a), Elem(b)) =>
        e.ops.lte(a, b)
    }
  }
  case class Top[T]() extends TopOrElem[T] { def ops = throw new Error("") }
  case class Elem[T:ElemOps](s: T) extends TopOrElem[T] {
    def ops = implicitly[ElemOps[T]]
  }

*/


  sealed trait Locality {
    def join(b: Locality): Locality = (this, b) match {
      case (AnyLoc, _) | (_, AnyLoc) =>
        AnyLoc
      case (RefSet(as), RefSet(bs)) =>
        RefSet(as union bs)
    }

    def meet(b: Locality): Locality = (this, b) match {
      case (AnyLoc, b) => b
      case (a, AnyLoc) => a
      case (RefSet(as), RefSet(bs)) =>
        RefSet(as intersect bs)
    }

    def lte(b: Locality): Boolean = (this, b) match {
      case (_, AnyLoc) => true
      case (AnyLoc, _) => false
      case (RefSet(as), RefSet(bs)) =>
        as.subsetOf(bs)
    }
  }
  case object AnyLoc extends Locality
  case class RefSet(s: Set[VarRef] = Set()) extends Locality {
    def this(ref: VarRef) = this(Set(ref))
  }
  object RefSet {
    def apply(ref: VarRef): RefSet = new RefSet(ref)
  }

  def joinAllLocalities(locs: List[Locality], init: Locality = RefSet()): Locality =
    (init /: locs) {
      case (locA, locB) => locA join locB
    }


  sealed trait AssignEff {
    def join(b: AssignEff): AssignEff = (this, b) match {
      case (AssignAny, _) | (_, AssignAny) =>
        AssignAny
      case (Assigns(as), Assigns(bs)) =>
        Assigns(joinAssigns(as, bs))
    }

    def meet(b: AssignEff): AssignEff = (this, b) match {
      case (AssignAny, b) => b
      case (a, AssignAny) => a
      case (Assigns(as), Assigns(bs)) =>
        Assigns(meetAssigns(as, bs))
    }

    def lte(b: AssignEff): Boolean = (this, b) match {
      case (_, AssignAny) => true
      case (AssignAny, _) => false
      case (Assigns(as), Assigns(bs)) =>
        lteAssigns(as, bs)
    }
  }
  case object AssignAny extends AssignEff
  case class Assigns(as: Map[Symbol, Locality] = Map()) extends AssignEff {
    def this(a: (Symbol, Locality)) = this(Map(a))
  }
  object Assigns {
    def apply(a: (Symbol, Locality)): Assigns = new Assigns(a)
  }

  def joinAssigns(a: Map[Symbol, Locality], b: Map[Symbol, Locality]) = {
    val commonSyms = a.keySet intersect b.keySet
    val (aCommon, aSeparate) = a.partition(commonSyms contains _._1)
    val (bCommon, bSeparate) = b.partition(commonSyms contains _._1)
    aSeparate ++ bSeparate ++ aCommon.map({
      case (aSym, aLoc) =>
        (aSym, aLoc join bCommon(aSym))
    })
  }

  def meetAssigns(a: Map[Symbol, Locality], b: Map[Symbol, Locality]) = {
    val commonSyms = a.keySet intersect b.keySet
    commonSyms.flatMap(sym => {
      (a(sym) meet b(sym)) match {
        case RefSet(s) if s.isEmpty => None
        case loc => Some((sym, loc))
      }
    }).toMap
  }

  def lteAssigns(a: Map[Symbol, Locality], b: Map[Symbol, Locality]) = {
    a.forall({
      case (aSym, aLoc) =>
        b.contains(aSym) && aLoc.lte(b(aSym))
    })
  }

  def joinAllAssignEffs(locs: List[AssignEff], init: AssignEff = Assigns()): AssignEff =
    (init /: locs) {
      case (effA, effB) => effA join effB
    }
}
