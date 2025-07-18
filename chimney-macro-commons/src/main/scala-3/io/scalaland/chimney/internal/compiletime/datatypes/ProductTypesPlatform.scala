package io.scalaland.chimney.internal.compiletime.datatypes

import io.scalaland.chimney.internal.compiletime.DefinitionsPlatform

import scala.collection.immutable.{ListMap, ListSet}

trait ProductTypesPlatform extends ProductTypes { this: DefinitionsPlatform =>

  import quotes.*, quotes.reflect.*

  protected object ProductType extends ProductTypesModule {

    object platformSpecific {

      def isParameterless(method: Symbol): Boolean =
        method.paramSymss.filterNot(_.exists(_.isType)).flatten.isEmpty

      def isDefaultConstructor(ctor: Symbol): Boolean =
        Type.platformSpecific.isPublic(ctor) && ctor.isClassConstructor && isParameterless(ctor)

      def isAccessor(accessor: Symbol): Boolean =
        Type.platformSpecific.isPublic(accessor) && accessor.isDefDef && isParameterless(accessor)

      // assuming isAccessor was tested earlier
      def isJavaGetter(getter: Symbol): Boolean =
        ProductTypes.BeanAware.isGetterName(getter.name)

      def isJavaSetter(setter: Symbol): Boolean =
        Type.platformSpecific.isPublic(setter) && setter.isDefDef && setter.paramSymss.flatten.size == 1 &&
          ProductTypes.BeanAware.isSetterName(setter.name)

      def isVar(setter: Symbol): Boolean =
        Type.platformSpecific.isPublic(setter) && (setter.isValDef || setter.isDefDef) && setter.flags.is(Flags.Mutable)

      def isJavaSetterOrVar(setter: Symbol): Boolean =
        isJavaSetter(setter) || isVar(setter)
    }

    import platformSpecific.*
    import Type.platformSpecific.*
    import Type.Implicits.*

    def isPOJO[A](implicit A: Type[A]): Boolean = {
      val tpeSym = TypeRepr.of(using A).typeSymbol
      val sym = if tpeSym.isAliasType then TypeRepr.of(using A).classSymbol.getOrElse(tpeSym) else tpeSym
      !A.isPrimitive && !(A <:< Type[String]) && sym.isClassDef && !sym.isAbstract &&
      publicPrimaryOrOnlyPublicConstructor(sym).isDefined
    }
    def isCaseClass[A](implicit A: Type[A]): Boolean = {
      val sym = TypeRepr.of(using A).typeSymbol
      sym.isClassDef && sym.flags.is(Flags.Case) && !sym.isAbstract && sym.primaryConstructor.isPublic
    }
    def isCaseObject[A](implicit A: Type[A]): Boolean = {
      val sym = TypeRepr.of(using A).typeSymbol
      sym.isPublic && sym.flags.is(Flags.Case | Flags.Module)
    }
    def isCaseVal[A](implicit A: Type[A]): Boolean = {
      def attempt(sym: Symbol): Boolean =
        sym.isPublic && sym.flags
          .is(Flags.Case | Flags.Enum) && (sym.flags.is(Flags.JavaStatic) || sym.flags.is(Flags.StableRealizable))
      attempt(TypeRepr.of(using A).typeSymbol) || attempt(TypeRepr.of(using A).termSymbol)
    }
    def isJavaEnumValue[A: Type]: Boolean =
      Type[A] <:< scala.quoted.Type.of[java.lang.Enum[?]] && !TypeRepr.of[A].typeSymbol.isAbstract
    def isJavaBean[A](implicit A: Type[A]): Boolean = {
      val sym = TypeRepr.of(using A).typeSymbol
      val mem = sym.declarations
      isPOJO[A] && mem.exists(isDefaultConstructor) && mem.exists(isJavaSetterOrVar)
    }

    private type CachedExtraction[A] = Option[Product.Extraction[A]]
    private val extractionCache = new Type.Cache[CachedExtraction]
    def parseExtraction[A: Type]: Option[Product.Extraction[A]] = extractionCache(Type[A])(
      Some(
        Product.Extraction(ListMap.from[String, Existential[Product.Getter[A, *]]] {
          import Type.platformSpecific.*
          val A = TypeRepr.of[A]
          val sym = A.typeSymbol

          def sameNamedSymbolIn(syms: Set[Symbol]): Symbol => Boolean = {
            val names = syms.map(_.name.trim).toSet
            sym2 => names(sym2.name.trim)
          }

          val (argVals, bodyVals) = {
            // case class fields appear once in sym.caseFields as vals and once in sym.declaredMethods as methods
            // additionally sometimes they appear twice! once as "val name" and once as "method name " (notice space at the end
            // of name). This breaks matching by order (tuples) but has to be fixed in a way that doesn't filter out fields
            // for normal cases.
            def sanitize(syms: List[Symbol]): List[Symbol] =
              syms.zipWithIndex
                .groupBy(_._1.name.trim)
                .view
                .map {
                  case (_, Seq(fieldIdx, _)) if fieldIdx._1.isDefDef => fieldIdx
                  case (_, Seq(_, fieldIdx)) if fieldIdx._1.isDefDef => fieldIdx
                  case (_, fieldIdxs)                                => fieldIdxs.head
                }
                .toList
                .sortBy(_._2)
                .map(_._1)

            // Make sure that: we only use public definitions, output is sorted by the order of definition
            def sortedPublicUnique(syms: List[Symbol]): ListSet[Symbol] =
              ListSet.from(sanitize(syms.filter(isPublic)).sorted)

            // To distinct between vals defined in constructor and in body
            val isArg = sameNamedSymbolIn(paramListsOf(A, sym.primaryConstructor).flatten.filter(isPublic).toSet)

            val caseFields = sortedPublicUnique(sym.caseFields)

            // As silly as it looks: when I tried to get rid of caseFields and handle everything with fieldMembers
            // the result was really bad. It probably can be done, but it's error prone at best.
            val (argFields, bodyFields) =
              sortedPublicUnique(sym.fieldMembers.filterNot(sameNamedSymbolIn(caseFields))).partition(isArg)

            (caseFields ++ argFields, bodyFields)
          }
          val accessorsAndGetters = ListSet.from(
            sym.methodMembers
              .filterNot(_.paramSymss.exists(_.exists(_.isType))) // remove methods with type parameters
              .filterNot(isGarbageSymbol)
              .filter(isAccessor)
              .filter(isPublic)
              .filterNot(sameNamedSymbolIn(argVals))
              .filterNot(sameNamedSymbolIn(bodyVals))
              .sorted
          )

          val isArgumentField = argVals
          val isBodyField = bodyVals
          val localDefinitions = (sym.declaredMethods ++ sym.declaredFields).toSet

          (argVals ++ bodyVals ++ accessorsAndGetters).map { getter =>
            val name = getter.name.trim
            val tpe = ExistentialType(returnTypeOf[Any](A, getter))
            def conformToIsGetters = !name.take(2).equalsIgnoreCase("is") || tpe.Underlying <:< Type[Boolean]
            name -> tpe.mapK[Product.Getter[A, *]] { implicit Tpe: Type[tpe.Underlying] => _ =>
              Product.Getter(
                sourceType =
                  if isArgumentField(getter) then Product.Getter.SourceType.ConstructorArgVal
                  else if isJavaGetter(getter) && conformToIsGetters then Product.Getter.SourceType.JavaBeanGetter
                  else if isBodyField(getter) then Product.Getter.SourceType.ConstructorBodyVal
                  else Product.Getter.SourceType.AccessorMethod,
                isInherited = !localDefinitions(getter),
                get =
                  // TODO: pathological cases like def foo[Unused]()()()
                  if getter.paramSymss.isEmpty then (in: Expr[A]) =>
                    in.asTerm.select(getter).appliedToArgss(Nil).asExprOf[tpe.Underlying]
                  else (in: Expr[A]) => in.asTerm.select(getter).appliedToNone.asExprOf[tpe.Underlying]
              )
            }
          }
        })
      )
    )

    private type CachedConstructor[A] = Option[Product.Constructor[A]]
    private val constructorCache = new Type.Cache[CachedConstructor]
    def parseConstructor[A: Type]: Option[Product.Constructor[A]] = constructorCache(Type[A]) {
      if isCaseObject[A] || isCaseVal[A] || isJavaEnumValue[A] then {
        val A = TypeRepr.of[A]
        // Workaround for different Symbol used when we obtain things from SealedHierarchies and passed by user as
        // Enum.Value.type
        val sym = if A.termSymbol != Symbol.noSymbol then A.termSymbol else A.typeSymbol

        if isCaseVal[A] || isJavaEnumValue[A] then Some(Product.Constructor(ListMap.empty, _ => Ref(sym).asExprOf[A]))
        else Some(Product.Constructor(ListMap.empty, _ => Ref(sym.companionModule).asExprOf[A]))
      } else if isPOJO[A] then {
        val A = TypeRepr.of[A]
        val sym = A.typeSymbol

        val unambiguousConstructor =
          publicPrimaryOrOnlyPublicConstructor(sym).getOrElse {
            // $COVERAGE-OFF$should never happen unless we messed up
            assertionFailed(s"Expected public constructor of ${Type.prettyPrint[A]}")
            // $COVERAGE-ON$
          }
        val paramss = paramListsOf(A, unambiguousConstructor)
        val paramNames = paramss.flatMap(_.map(param => param -> param.name)).toMap
        val paramTypes = paramsWithTypes(A, unambiguousConstructor, isConstructor = true)
        val defaultValues = paramss.flatten.zipWithIndex.collect {
          case (param, idx) if param.flags.is(Flags.HasDefault) =>
            val mod = sym.companionModule
            val scala2default = caseClassApplyDefaultScala2(idx + 1)
            val scala3default = caseClassApplyDefaultScala3(idx + 1)
            val default =
              (mod.declaredMethod(scala2default) ++ mod.declaredMethod(scala3default)).headOption.getOrElse {
                // $COVERAGE-OFF$should never happen unless we messed up
                assertionFailed(
                  s"Expected that ${Type.prettyPrint[A]}'s constructor parameter `$param` would have default value: attempted `$scala2default` and `$scala3default`, found: ${mod.declaredMethods}"
                )
                // $COVERAGE-ON$
              }
            paramNames(param) -> Ref(mod).select(default)
        }.toMap
        val constructorParameters = ListMap.from(paramss.flatMap(_.map { param =>
          val name = paramNames(param)
          val tpe = ExistentialType(fromUntyped[Any](paramTypes(name)))
          name ->
            tpe.mapK { implicit Tpe: Type[tpe.Underlying] => _ =>
              Product.Parameter(
                Product.Parameter.TargetType.ConstructorParameter,
                defaultValues.get(name).map(_.asExprOf[tpe.Underlying])
              )
            }
        }))

        import Type.platformSpecific.symbolOrdering

        val setters = sym.methodMembers
          .filterNot(isGarbageSymbol)
          .filter(isJavaSetterOrVar)
          .sorted // Scala 2's syms are sorted by position, in Scala 3 we have to sort them
          .map { setter =>
            val n = setter.name
            val name = if isVar(setter) then n.stripSuffix("_$eq").stripSuffix("_=") else n
            name -> setter
          }
          .filter { case (name, _) => !paramTypes.keySet.contains(name) } // _exact_ name match!
          .map { case (name, setter) =>
            val tpe = ExistentialType(fromUntyped[Any](paramsWithTypes(A, setter, isConstructor = false).head._2))
            (
              name,
              setter,
              tpe.mapK[Product.Parameter](_ =>
                _ =>
                  Product.Parameter(
                    targetType =
                      Product.Parameter.TargetType.SetterParameter(ExistentialType(returnTypeOf[Any](A, setter))),
                    defaultValue = None
                  )
              )
            )
          }

        val setterParameters = ListMap.from(setters.map { case (name, _, param) => name -> param })
        type Setter[B] = (Expr[A], Expr[B]) => Expr[Unit]
        val setterExprs = setters.map { case (name, symbol, param) =>
          name ->
            param.mapK[Setter] {
              implicit Param: Type[param.Underlying] => x => (exprA: Expr[A], exprArg: Expr[param.Underlying]) =>
                Block(List(exprA.asTerm.select(symbol).appliedTo(exprArg.asTerm)), Expr.Unit.asTerm).asExprOf[Unit]
            }
        }.toMap

        val parameters: Product.Parameters = constructorParameters ++ setterParameters

        val constructor: Product.Arguments => Expr[A] = arguments => {
          val (constructorArguments, setterArguments) = checkArguments[A](parameters, arguments)

          def newExpr = {
            // new A
            val select = New(TypeTree.of[A]).select(unambiguousConstructor)
            // new A[B1, B2, ...] vs new A
            val tree = if A.typeArgs.nonEmpty then select.appliedToTypes(A.typeArgs) else select
            // new A... or new A() or new A(b1, b2), ...
            tree
              .appliedToArgss(paramss.map(_.map(param => constructorArguments(paramNames(param)).value.asTerm)))
              .asExprOf[A]
          }

          if setterArguments.isEmpty then {
            newExpr
          } else {
            PrependDefinitionsTo
              .prependVal[A](newExpr, ExprPromise.NameGenerationStrategy.FromType)
              .use { exprA =>
                Expr.block(
                  setterArguments.map { case (name, exprArg) =>
                    val setter = setterExprs(name)
                    assert(exprArg.Underlying =:= setter.Underlying)
                    import setter.{Underlying, value as setterExpr}
                    setterExpr(exprA, exprArg.value.asInstanceOf[Expr[setter.Underlying]])
                  }.toList,
                  exprA
                )
              }
          }
        }

        Some(Product.Constructor(parameters, constructor))
      } else None
    }

    def exprAsInstanceOfMethod[A: Type](args: List[ListMap[String, ??]])(expr: Expr[Any]): Product.Constructor[A] = {
      val parameters: Product.Parameters = ListMap.from(for {
        list <- args
        pair <- list.toList
        (paramName, paramType) = pair
      } yield {
        import paramType.Underlying as ParamType
        paramName -> Existential[Product.Parameter, ParamType](
          Product.Parameter(Product.Parameter.TargetType.ConstructorParameter, None)
        )
      })

      val constructor: Product.Arguments => Expr[A] = arguments => {
        val (constructorArguments, _) = checkArguments[A](parameters, arguments)

        val methodType: ?? = args.foldRight[??](Type[A].as_??) { (paramList, resultType) =>
          val fnType = fnTypeByArity.getOrElse(
            paramList.size,
            // TODO: handle FunctionXXL
            // $COVERAGE-OFF$should never happen unless we messed up
            assertionFailed(s"Expected arity between 0 and 22 into ${Type.prettyPrint[A]}, got: ${paramList.size}")
            // $COVERAGE-ON$
          )
          val paramTypes = paramList.view.values.map(p => TypeRepr.of(using p.Underlying)).toVector

          fromUntyped(
            fnType.appliedTo((paramTypes :+ TypeRepr.of(using resultType.Underlying)).toList).dealias.simplified
          ).as_??
        }

        import methodType.Underlying as MethodType
        val tree = expr.asInstanceOfExpr[MethodType].asTerm
        args
          .foldLeft(tree) { (result, list) =>
            val method: Symbol = result.tpe.typeSymbol.methodMember("apply").head
            result
              .select(method)
              .appliedToArgs(list.map { (paramName, _) =>
                constructorArguments(paramName).value.asTerm
              }.toList)
          }
          .asExprOf[A]
      }

      Product.Constructor[A](parameters, constructor)
    }

    private lazy val fnTypeByArity = Map(
      0 -> TypeRepr.of[scala.Function0],
      1 -> TypeRepr.of[scala.Function1],
      2 -> TypeRepr.of[scala.Function2],
      3 -> TypeRepr.of[scala.Function3],
      4 -> TypeRepr.of[scala.Function4],
      5 -> TypeRepr.of[scala.Function5],
      6 -> TypeRepr.of[scala.Function6],
      7 -> TypeRepr.of[scala.Function7],
      8 -> TypeRepr.of[scala.Function8],
      9 -> TypeRepr.of[scala.Function9],
      10 -> TypeRepr.of[scala.Function10],
      11 -> TypeRepr.of[scala.Function11],
      12 -> TypeRepr.of[scala.Function12],
      13 -> TypeRepr.of[scala.Function13],
      14 -> TypeRepr.of[scala.Function14],
      15 -> TypeRepr.of[scala.Function15],
      16 -> TypeRepr.of[scala.Function16],
      17 -> TypeRepr.of[scala.Function17],
      18 -> TypeRepr.of[scala.Function18],
      19 -> TypeRepr.of[scala.Function19],
      20 -> TypeRepr.of[scala.Function20],
      21 -> TypeRepr.of[scala.Function21],
      22 -> TypeRepr.of[scala.Function22]
    )

    private val isGarbageSymbol = ((s: Symbol) => s.name) andThen ProductTypes.isGarbageName
  }
}
