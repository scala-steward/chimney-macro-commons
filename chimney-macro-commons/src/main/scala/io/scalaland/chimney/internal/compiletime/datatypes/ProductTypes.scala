package io.scalaland.chimney.internal.compiletime.datatypes

import io.scalaland.chimney.internal.compiletime.Definitions

import scala.collection.compat.*
import scala.collection.immutable.ListMap

trait ProductTypes { this: Definitions =>

  /** Describes all types which could be considered products in a very loose way.
    *
    * For type to be considered "product" it has to be:
    *   - non abstract
    *   - have a public (primary) constructor
    *
    * If it's a "product" then we are able to provide both a way to construct it as well as a way to extract its
    * properties. This is rather unrestricted since:
    *   - our "constructor" allows passing arguments to Java Bean setters
    *   - our properties include: `def`s without arguments, Java Bean getters and it's the code using the extractors and
    *     constructors that should check the type of getter/constructor argument.
    *
    * In case we don't need a "product" per se, but rather any instantiable type to instantiate or any type to obtain
    * its methods, we can use `unapply` from `Extraction` or `Construction`.
    */
  final protected case class Product[A](extraction: Product.Extraction[A], construction: Product.Constructor[A])
  protected object Product {

    final case class Getter[From, A](sourceType: Getter.SourceType, isInherited: Boolean, get: Expr[From] => Expr[A])
    object Getter {

      /** Let us decide whether or now we can use the getter based on configuration */
      sealed trait SourceType extends scala.Product with Serializable
      object SourceType {

        /** `val`/`var` initialized by constructor as a parameter */
        case object ConstructorArgVal extends SourceType

        /** `val`/`lazy val`/`var` initialized by constructor in the body */
        case object ConstructorBodyVal extends SourceType

        /** `def` without parameters which cannot be treated as Java Bean getter */
        case object AccessorMethod extends SourceType

        /** `def` without parameters which name starts with `get` or `is` if it returns `Boolean` */
        case object JavaBeanGetter extends SourceType
      }
    }
    final type Getters[From] = ListMap[String, Existential[Getter[From, *]]]

    /** Let us obtain a list of: vals, lazy vals and parameterless defs that we can always call. */
    final case class Extraction[From](extraction: Getters[From])
    object Extraction {
      def unapply[From](From: Type[From]): Option[Getters[From]] =
        ProductType.parseExtraction(From).map(getters => getters.extraction)
    }

    final case class Parameter[A](targetType: Parameter.TargetType, defaultValue: Option[Expr[A]])
    object Parameter {
      sealed trait TargetType extends scala.Product with Serializable
      object TargetType {

        /** When constructing, value will be passed as constructor argument */
        case object ConstructorParameter extends TargetType

        /** When constructing, value will be passed as setter argument */
        final case class SetterParameter(returnedType: ??) extends TargetType {
          override def toString: String =
            s"SetterParameter(returnedType = ${Type.prettyPrint(returnedType.Underlying)})"
        }
      }
    }
    final type Parameters = ListMap[String, Existential[Parameter]]

    final type Arguments = Map[String, ExistentialExpr]

    /** Let us obtain a list of primary constructor's parameters as well as setter parameters, as well as a method of
      * taking all computed arguments and turning it into constructed value.
      */
    final case class Constructor[To](parameters: Parameters, constructor: Arguments => Expr[To])
    object Constructor {
      def unapply[To](To: Type[To]): Option[(Parameters, Arguments => Expr[To])] =
        ProductType.parseConstructor(To).map(constructor => constructor.parameters -> constructor.constructor)

      def exprAsInstanceOfMethod[To: Type](args: List[ListMap[String, ??]])(expr: ExistentialExpr): Constructor[To] = {
        import Type.Implicits.*
        ProductType.exprAsInstanceOfMethod[To](args)(expr.asInstanceOfExpr[Any])
      }
    }
  }

  protected val ProductType: ProductTypesModule
  protected trait ProductTypesModule { this: ProductType.type =>

    /** Any class with a public constructor... explicitly excluding: primitives, String and Java enums */
    def isPOJO[A](implicit A: Type[A]): Boolean

    /** Class defined with "case class" */
    def isCaseClass[A](implicit A: Type[A]): Boolean

    /** Class defined with "case object" */
    def isCaseObject[A](implicit A: Type[A]): Boolean

    /** Scala 3 enum's case without parameters (a "val" under the hood, NOT an "object") */
    def isCaseVal[A](implicit A: Type[A]): Boolean

    /** Java enum value - not the abstract enum type, but the concrete enum value */
    def isJavaEnumValue[A](implicit A: Type[A]): Boolean

    /** Any POJO with a public DEFAULT constructor... and at least 1 setter or var */
    def isJavaBean[A](implicit A: Type[A]): Boolean

    def parseExtraction[A: Type]: Option[Product.Extraction[A]]
    def parseConstructor[A: Type]: Option[Product.Constructor[A]]
    final def parse[A: Type]: Option[Product[A]] = parseExtraction[A].zip(parseConstructor[A]).headOption.map {
      case (getters, constructor) => Product(getters, constructor)
    }
    final def unapply[A](tpe: Type[A]): Option[Product[A]] = parse(using tpe)

    def exprAsInstanceOfMethod[A: Type](args: List[ListMap[String, ??]])(expr: Expr[Any]): Product.Constructor[A]

    // defaults methods are 1-indexed
    protected def classNewDefaultScala2(idx: Int): String = "<init>$default$" + idx
    protected def caseClassApplyDefaultScala2(idx: Int): String = "apply$default$" + idx
    protected def caseClassApplyDefaultScala3(idx: Int): String = "$lessinit$greater$default$" + idx

    // skipping on setter should not create a invalid expression, whether or not is should be called depends on caller
    private val settersCanBeIgnored: ((String, Existential[Product.Parameter])) => Boolean =
      _._2.value.targetType == Product.Parameter.TargetType.ConstructorParameter

    protected def checkArguments[A: Type](
        parameters: Product.Parameters,
        arguments: Product.Arguments
    ): (Product.Arguments, Product.Arguments) = {
      val missingArguments = parameters.filter(settersCanBeIgnored).keySet diff arguments.keySet
      if (missingArguments.nonEmpty) {
        // $COVERAGE-OFF$should never happen unless we messed up
        val missing = missingArguments.mkString(", ")
        val provided = arguments.keys.mkString(", ")
        assertionFailed(
          s"Constructor of ${Type.prettyPrint[A]} expected arguments: $missing but they were not provided, what was provided: $provided"
        )
        // $COVERAGE-ON$
      }

      parameters.foreach { case (name, param) =>
        import param.Underlying as Param
        // setter might be absent, so we cannot assume that argument for it is in a map
        arguments.get(name).foreach { argument =>
          if (!(argument.Underlying <:< Param)) {
            // $COVERAGE-OFF$should never happen unless we messed up
            assertionFailed(
              s"Constructor of ${Type.prettyPrint[A]} expected expr for parameter $param of type ${Type
                  .prettyPrint[param.Underlying]}, instead got ${Expr.prettyPrint(argument.value)} ${Type.prettyPrint(argument.Underlying)}"
            )
            // $COVERAGE-ON$
          }
        }
      }

      val (params, setters) =
        parameters.partition(_._2.value.targetType == Product.Parameter.TargetType.ConstructorParameter)

      val constructorParameters = params.keySet
      val constructorArguments = ListMap.from(arguments.view.filterKeys(constructorParameters))

      val setterParameters = setters.keySet
      val setterArguments = ListMap.from(arguments.view.filterKeys(setterParameters))

      constructorArguments -> setterArguments
    }
  }

  implicit class ProductTypeOps[A](private val tpe: Type[A]) {

    def isCaseClass: Boolean = ProductType.isCaseClass(tpe)
    def isCaseObject: Boolean = ProductType.isCaseObject(tpe)
    def isJavaBean: Boolean = ProductType.isJavaBean(tpe)
    def isPOJO: Boolean = ProductType.isPOJO(tpe)
  }
}
object ProductTypes {

  object BeanAware {

    implicit private class RegexpOps(regexp: scala.util.matching.Regex) {

      def isMatching(value: String): Boolean = regexp.pattern.matcher(value).matches() // 2.12 doesn't have .matches
    }

    private val getAccessor = raw"(?i)get(.)(.*)".r
    private val isAccessor = raw"(?i)is(.)(.*)".r
    val isGetterName: String => Boolean = name => getAccessor.isMatching(name) || isAccessor.isMatching(name)

    val dropGetIs: String => String = {
      case getAccessor(head, tail) => head.toLowerCase + tail
      case isAccessor(head, tail)  => head.toLowerCase + tail
      case other                   => other
    }

    private val setAccessor = raw"(?i)set(.)(.*)".r
    val isSetterName: String => Boolean = name => setAccessor.isMatching(name)

    val dropSet: String => String = {
      case setAccessor(head, tail) => head.toLowerCase + tail
      case other                   => other
    }
  }

  // methods we can drop from searching scope
  private val garbage = Set(
    // constructor
    "<init>",
    "$init$",
    // case class generated
    "copy",
    // scala.Product methods
    "##",
    "canEqual",
    "productArity",
    "productElement",
    "productElementName",
    "productElementNames",
    "productIterator",
    "productPrefix",
    // java.lang.Object methods
    "equals",
    "finalize",
    "hashCode",
    "toString",
    "clone",
    "synchronized",
    "wait",
    "notify",
    "notifyAll",
    "getClass",
    "asInstanceOf",
    "isInstanceOf"
  )
  // default arguments has name method$default$index
  private val defaultElement = raw"$$default$$"
  val isGarbageName: String => Boolean = name => garbage(name) || name.contains(defaultElement)
}
