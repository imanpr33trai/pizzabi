package com.pizzora.contracts

data class PizzaBuilderEvaluation(
  val isValid: Boolean,
  val issues: List<CartValidationIssueDto>,
  val priceBreakdown: PizzaPriceBreakdownDto,
  val unitPricePaise: Long,
  val lineTotalPaise: Long,
  val toppings: List<CartToppingViewDto>,
  val customization: CartItemCustomizationSummaryDto,
  val configurationSignature: String,
  val effectiveSizeCode: SizeCode?,
  val effectiveCheeseId: String?,
  val effectiveSauceId: String?,
  val effectiveRightSauceId: String?,
) {
  fun toQuoteResponse(): PizzaBuilderQuoteResponse =
    PizzaBuilderQuoteResponse(
      isValid = isValid,
      unitPricePaise = unitPricePaise,
      lineTotalPaise = lineTotalPaise,
      priceBreakdown = priceBreakdown,
      configurationSignature = configurationSignature,
      customization = customization,
      toppings = toppings,
      issues = issues,
    )
}

object PizzaBuilderEngine {
  const val MAX_ITEM_QUANTITY = 25
  const val HALF_PRICE_BPS = 5_000

  fun normalizeToppingSelections(input: CartItemInputDto): List<CartToppingInputDto> {
    if (input.toppingSelections.isNotEmpty()) {
      return input.toppingSelections
    }
    return input.toppingIds
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { toppingId ->
        CartToppingInputDto(
          toppingId = toppingId,
          placement = ToppingPlacement.WHOLE,
          quantity = 1,
        )
      }
  }

  fun computeConfigurationSignature(
    input: CartItemInputDto,
    menu: PublicMenuResponse? = null,
  ): String {
    val products = menu?.products?.associateBy { it.id }.orEmpty()
    val leftProduct = products[input.productId]
    val rightProduct = input.rightProductId?.let { products[it] }
    val effCheeseId =
      input.cheeseId?.trim()?.takeIf { it.isNotEmpty() }
        ?: leftProduct?.defaultCheeseId?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""
    val effSauceId =
      input.sauceId?.trim()?.takeIf { it.isNotEmpty() }
        ?: leftProduct?.defaultSauceId?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""
    val effRightProductId = if (input.isHalfAndHalf) (input.rightProductId?.trim() ?: "") else ""
    val effRightVariantId = if (input.isHalfAndHalf) (input.rightVariantId?.trim() ?: "") else ""
    val effRightSauceId =
      if (input.isHalfAndHalf) {
        input.rightSauceId?.trim()?.takeIf { it.isNotEmpty() }
          ?: rightProduct?.defaultSauceId?.trim()?.takeIf { it.isNotEmpty() }
          ?: effSauceId
      } else {
        ""
      }
    val removedCsv =
      input.removedIncludedToppingIds
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .joinToString(",")
    val selectionsCsv =
      normalizeToppingSelections(input)
        .sortedWith(compareBy({ it.toppingId.trim() }, { it.placement.name }))
        .joinToString(",") { "${it.toppingId.trim()}:${it.placement.name}:${it.quantity}" }

    return buildString {
      append(input.productId.trim())
      append('|').append(input.variantId.trim())
      append('|').append(input.crustId.trim())
      append('|').append(effCheeseId)
      append('|').append(input.extraCheese)
      append('|').append(effSauceId)
      append('|').append(input.extraSauce)
      append('|').append(input.isHalfAndHalf)
      append('|').append(effRightProductId)
      append('|').append(effRightVariantId)
      append('|').append(effRightSauceId)
      append('|').append(removedCsv)
      append('|').append(selectionsCsv)
    }
  }

  fun evaluate(
    input: CartItemInputDto,
    menu: PublicMenuResponse,
  ): PizzaBuilderEvaluation {
    val productsById = menu.products.associateBy { it.id }
    val crustsById = menu.crusts.associateBy { it.id }
    val toppingsById = menu.toppings.associateBy { it.id }
    val cheesesById = menu.cheeses.associateBy { it.id }
    val saucesById = menu.sauces.associateBy { it.id }
    val sizeRulesByCode = menu.sizeRules.associateBy { it.sizeCode }

    val issues = mutableListOf<CartValidationIssueDto>()

    if (input.quantity < 1 || input.quantity > MAX_ITEM_QUANTITY) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.INVALID_QUANTITY,
          message = "Item quantity must be between 1 and $MAX_ITEM_QUANTITY.",
        )
    }

    val leftProduct = productsById[input.productId]
    if (leftProduct == null || !leftProduct.isActive) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.PRODUCT_UNAVAILABLE,
          message = "Selected pizza is unavailable.",
        )
    }

    val leftVariant = leftProduct?.variants?.firstOrNull { it.id == input.variantId }
    if (leftProduct != null) {
      if (leftVariant == null) {
        val belongsToOtherProduct =
          menu.products.any { other ->
            other.id != leftProduct.id && other.variants.any { it.id == input.variantId }
          }
        issues +=
          CartValidationIssueDto(
            code =
              if (belongsToOtherProduct) {
                CartValidationIssueCode.VARIANT_PRODUCT_MISMATCH
              } else {
                CartValidationIssueCode.VARIANT_UNAVAILABLE
              },
            message =
              if (belongsToOtherProduct) {
                "Selected size does not belong to ${leftProduct.name}."
              } else {
                "Selected pizza size is unavailable."
              },
          )
      } else if (!leftVariant.isActive) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.VARIANT_UNAVAILABLE,
            message = "Selected pizza size is currently disabled.",
          )
      }
    }

    val sizeRule =
      leftVariant?.let { variant ->
        if (sizeRulesByCode.isNotEmpty()) {
          val found = sizeRulesByCode[variant.sizeCode]
          if (found == null || !found.isActive) {
            issues +=
              CartValidationIssueDto(
                code = CartValidationIssueCode.SIZE_UNAVAILABLE,
                message = "Size ${variant.sizeCode.name} is currently unavailable.",
              )
          }
          found
        } else {
          defaultSizeRule(variant.sizeCode)
        }
      }

    val crust = crustsById[input.crustId]
    if (crust == null || !crust.isActive) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.CRUST_UNAVAILABLE,
          message = "Selected crust is unavailable.",
        )
    }

    val effCheeseId =
      input.cheeseId?.trim()?.takeIf { it.isNotEmpty() }
        ?: leftProduct?.defaultCheeseId?.trim()?.takeIf { it.isNotEmpty() }
    val cheese = effCheeseId?.let { cheesesById[it] }
    if (effCheeseId != null) {
      if (cheese == null || !cheese.isActive) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.CHEESE_UNAVAILABLE,
            message = "Selected cheese is unavailable.",
          )
      } else if (input.extraCheese && !cheese.supportsExtra) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.EXTRA_CHEESE_UNAVAILABLE,
            message = "Extra cheese is not supported for ${cheese.name}.",
          )
      }
    } else if (input.extraCheese) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.EXTRA_CHEESE_UNAVAILABLE,
          message = "Extra cheese requires a valid active cheese selection.",
        )
    }

    val effSauceId =
      input.sauceId?.trim()?.takeIf { it.isNotEmpty() }
        ?: leftProduct?.defaultSauceId?.trim()?.takeIf { it.isNotEmpty() }
    val leftSauce = effSauceId?.let { saucesById[it] }
    if (effSauceId != null) {
      if (leftSauce == null || !leftSauce.isActive) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.SAUCE_UNAVAILABLE,
            message = "Selected sauce is unavailable.",
          )
      } else if (input.extraSauce && !leftSauce.supportsExtra) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.EXTRA_SAUCE_UNAVAILABLE,
            message = "Extra sauce is not supported for ${leftSauce.name}.",
          )
      }
    } else if (input.extraSauce) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.EXTRA_SAUCE_UNAVAILABLE,
          message = "Extra sauce requires a valid active sauce selection.",
        )
    }

    var rightProduct: ProductDto? = null
    var rightVariant: ProductVariantDto? = null
    var effRightSauceId: String? = null
    var rightSauce: SauceOptionDto? = null

    if (input.isHalfAndHalf) {
      if (leftProduct != null && !leftProduct.supportsHalfAndHalf) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED,
            message = "${leftProduct.name} cannot be customized as half-and-half.",
          )
      }
      if (sizeRule != null && !sizeRule.supportsHalfAndHalf) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED,
            message = "Half-and-half is not available for ${sizeRule.displayName}.",
          )
      }
      if (crust != null && !crust.supportsHalfAndHalf) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED,
            message = "Half-and-half is not supported with ${crust.name} crust.",
          )
      }

      val rightProdId = input.rightProductId?.trim()?.takeIf { it.isNotEmpty() }
      val rightVarId = input.rightVariantId?.trim()?.takeIf { it.isNotEmpty() }
      rightProduct = rightProdId?.let { productsById[it] }
      if (rightProduct == null || !rightProduct.isActive) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.RIGHT_HALF_INVALID,
            message = "Right-half pizza selection is unavailable or missing.",
          )
      } else {
        if (!rightProduct.supportsHalfAndHalf) {
          issues +=
            CartValidationIssueDto(
              code = CartValidationIssueCode.HALF_AND_HALF_UNSUPPORTED,
              message = "${rightProduct.name} cannot be used on a half-and-half pizza.",
            )
        }
        rightVariant = rightVarId?.let { vid -> rightProduct.variants.firstOrNull { it.id == vid } }
        if (rightVariant == null || !rightVariant.isActive) {
          issues +=
            CartValidationIssueDto(
              code = CartValidationIssueCode.RIGHT_HALF_INVALID,
              message = "Right-half pizza size variant is unavailable.",
            )
        } else if (leftVariant != null && rightVariant.sizeCode != leftVariant.sizeCode) {
          issues +=
            CartValidationIssueDto(
              code = CartValidationIssueCode.RIGHT_HALF_INVALID,
              message = "Both halves must share the same pizza size.",
            )
        }
      }

      effRightSauceId =
        input.rightSauceId?.trim()?.takeIf { it.isNotEmpty() }
          ?: rightProduct?.defaultSauceId?.trim()?.takeIf { it.isNotEmpty() }
          ?: effSauceId
      rightSauce = effRightSauceId?.let { saucesById[it] }
      if (effRightSauceId != null) {
        if (rightSauce == null || !rightSauce.isActive) {
          issues +=
            CartValidationIssueDto(
              code = CartValidationIssueCode.SAUCE_UNAVAILABLE,
              message = "Selected right-half sauce is unavailable.",
            )
        } else if (input.extraSauce && !rightSauce.supportsExtra) {
          issues +=
            CartValidationIssueDto(
              code = CartValidationIssueCode.EXTRA_SAUCE_UNAVAILABLE,
              message = "Extra sauce is not supported for ${rightSauce.name}.",
            )
        }
      }
    }

    val allowedIncludedIds =
      buildSet {
        leftProduct?.includedToppingIds?.forEach { add(it) }
        if (input.isHalfAndHalf) {
          rightProduct?.includedToppingIds?.forEach { add(it) }
        }
      }
    val seenRemovedIds = mutableSetOf<String>()
    val removedIncludedNames = mutableListOf<String>()
    for (rawId in input.removedIncludedToppingIds) {
      val trimmed = rawId.trim()
      if (trimmed.isEmpty()) continue
      if (!seenRemovedIds.add(trimmed)) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.DUPLICATE_SELECTION,
            message = "Duplicate removed included topping selection.",
          )
        continue
      }
      if (trimmed !in allowedIncludedIds) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.INCLUDED_TOPPING_INVALID,
            message = "Topping cannot be removed because it is not included on this pizza.",
          )
      } else {
        toppingsById[trimmed]?.let { removedIncludedNames += it.name }
      }
    }

    val selections = normalizeToppingSelections(input)
    val seenToppingPairs = mutableSetOf<Pair<String, ToppingPlacement>>()
    val toppingViews = mutableListOf<CartToppingViewDto>()

    var leftHalfToppingLoad = 0
    var rightHalfToppingLoad = 0
    var extraHalfWeightUnits = 0
    var toppingsPricePaise = 0L

    for (sel in selections) {
      val tid = sel.toppingId.trim()
      val pair = tid to sel.placement
      if (!seenToppingPairs.add(pair)) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.DUPLICATE_SELECTION,
            message = "Duplicate topping selection for ${sel.placement.name}.",
          )
        continue
      }

      val topping = toppingsById[tid]
      if (topping == null || !topping.isActive) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.TOPPING_UNAVAILABLE,
            message = "Selected topping is unavailable.",
          )
      }

      if (!input.isHalfAndHalf && sel.placement != ToppingPlacement.WHOLE) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.TOPPING_PLACEMENT_INVALID,
            message = "Half-pizza topping placement requires half-and-half mode.",
          )
      } else if (sel.placement != ToppingPlacement.WHOLE && topping != null && !topping.supportsHalfAndHalf) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.TOPPING_PLACEMENT_INVALID,
            message = "${topping.name} cannot be applied to a single half.",
          )
      }

      val maxPerToppingQty = topping?.maxQuantity ?: 2
      if (sel.quantity < 1 || sel.quantity > maxPerToppingQty) {
        issues +=
          CartValidationIssueDto(
            code = CartValidationIssueCode.INVALID_QUANTITY,
            message =
              "${topping?.name ?: "Topping"} quantity must be between 1 and $maxPerToppingQty.",
          )
      }

      val includedOnLeft = leftProduct?.includedToppingIds?.contains(tid) == true
      val includedOnRight =
        if (input.isHalfAndHalf) {
          rightProduct?.includedToppingIds?.contains(tid) == true
        } else {
          includedOnLeft
        }
      val notRemoved = tid !in seenRemovedIds
      val isIncluded =
        notRemoved &&
          when (sel.placement) {
            ToppingPlacement.WHOLE -> includedOnLeft && (!input.isHalfAndHalf || includedOnRight)
            ToppingPlacement.LEFT -> includedOnLeft
            ToppingPlacement.RIGHT -> input.isHalfAndHalf && includedOnRight
          }

      val clampedQty = sel.quantity.coerceAtLeast(0)
      when (sel.placement) {
        ToppingPlacement.WHOLE -> {
          leftHalfToppingLoad += clampedQty
          rightHalfToppingLoad += clampedQty
        }
        ToppingPlacement.LEFT -> leftHalfToppingLoad += clampedQty
        ToppingPlacement.RIGHT -> rightHalfToppingLoad += clampedQty
      }

      val billableQty = if (isIncluded) maxOf(0, clampedQty - 1) else clampedQty
      extraHalfWeightUnits +=
        when (sel.placement) {
          ToppingPlacement.WHOLE -> billableQty * 2
          ToppingPlacement.LEFT, ToppingPlacement.RIGHT -> billableQty
        }

      val unitRate =
        if (topping == null) {
          0L
        } else {
          when (sel.placement) {
            ToppingPlacement.WHOLE -> topping.extraPricePaise
            ToppingPlacement.LEFT, ToppingPlacement.RIGHT ->
              Money.bpsOf(topping.extraPricePaise, HALF_PRICE_BPS)
          }
        }
      val selectionTotal = unitRate * billableQty.toLong()
      toppingsPricePaise += selectionTotal

      toppingViews +=
        CartToppingViewDto(
          toppingId = tid,
          name = topping?.name ?: "Unavailable Topping",
          isVeg = topping?.isVeg ?: true,
          extraPricePaise = topping?.extraPricePaise ?: 0L,
          isAvailable = topping?.isActive == true,
          placement = sel.placement,
          quantity = sel.quantity,
          isIncluded = isIncluded,
          unitPricePaise = unitRate,
          totalPricePaise = selectionTotal,
        )
    }

    val effectiveMaxToppings =
      listOfNotNull(
        sizeRule?.maxToppings,
        leftProduct?.maxToppingsOverride,
        if (input.isHalfAndHalf) rightProduct?.maxToppingsOverride else null,
      ).minOrNull()

    if (effectiveMaxToppings != null && maxOf(leftHalfToppingLoad, rightHalfToppingLoad) > effectiveMaxToppings) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.TOPPING_LIMIT_EXCEEDED,
          message = "Maximum $effectiveMaxToppings toppings allowed for this pizza size.",
        )
    }

    if (sizeRule != null && extraHalfWeightUnits > sizeRule.maxExtraToppings * 2) {
      issues +=
        CartValidationIssueDto(
          code = CartValidationIssueCode.EXTRA_TOPPING_LIMIT_EXCEEDED,
          message = "Maximum ${sizeRule.maxExtraToppings} extra toppings allowed for ${sizeRule.displayName}.",
        )
    }

    val basePricePaise = leftVariant?.pricePaise ?: 0L
    val halfAndHalfSurchargePaise =
      if (input.isHalfAndHalf && rightVariant != null) {
        maxOf(0L, rightVariant.pricePaise - basePricePaise)
      } else {
        0L
      }
    val crustPricePaise = crust?.extraPricePaise ?: 0L
    val cheesePricePaise = cheese?.extraPricePaise ?: 0L
    val extraCheesePricePaise =
      if (input.extraCheese && cheese != null && cheese.supportsExtra) {
        cheese.extraCheesePricePaise
      } else {
        0L
      }

    val saucePricePaise =
      if (!input.isHalfAndHalf || effSauceId == effRightSauceId) {
        leftSauce?.extraPricePaise ?: 0L
      } else {
        Money.bpsOf(leftSauce?.extraPricePaise ?: 0L, HALF_PRICE_BPS) +
          Money.bpsOf(rightSauce?.extraPricePaise ?: 0L, HALF_PRICE_BPS)
      }

    val extraSaucePricePaise =
      if (!input.extraSauce) {
        0L
      } else if (!input.isHalfAndHalf || effSauceId == effRightSauceId) {
        if (leftSauce?.supportsExtra == true) leftSauce.extraSaucePricePaise else 0L
      } else {
        val leftExtra =
          if (leftSauce?.supportsExtra == true) {
            Money.bpsOf(leftSauce.extraSaucePricePaise, HALF_PRICE_BPS)
          } else {
            0L
          }
        val rightExtra =
          if (rightSauce?.supportsExtra == true) {
            Money.bpsOf(rightSauce.extraSaucePricePaise, HALF_PRICE_BPS)
          } else {
            0L
          }
        leftExtra + rightExtra
      }

    val unitPricePaise =
      basePricePaise +
        halfAndHalfSurchargePaise +
        crustPricePaise +
        cheesePricePaise +
        extraCheesePricePaise +
        saucePricePaise +
        extraSaucePricePaise +
        toppingsPricePaise
    val safeQty = input.quantity.coerceAtLeast(0).toLong()
    val lineTotalPaise = unitPricePaise * safeQty

    val priceBreakdown =
      PizzaPriceBreakdownDto(
        basePricePaise = basePricePaise,
        halfAndHalfSurchargePaise = halfAndHalfSurchargePaise,
        crustPricePaise = crustPricePaise,
        cheesePricePaise = cheesePricePaise,
        extraCheesePricePaise = extraCheesePricePaise,
        saucePricePaise = saucePricePaise,
        extraSaucePricePaise = extraSaucePricePaise,
        toppingsPricePaise = toppingsPricePaise,
        unitPricePaise = unitPricePaise,
        lineTotalPaise = lineTotalPaise,
      )

    val signature = computeConfigurationSignature(input, menu)
    val customization =
      CartItemCustomizationSummaryDto(
        isHalfAndHalf = input.isHalfAndHalf,
        rightProductId = if (input.isHalfAndHalf) rightProduct?.id ?: input.rightProductId else null,
        rightProductName = if (input.isHalfAndHalf) rightProduct?.name else null,
        rightVariantId = if (input.isHalfAndHalf) rightVariant?.id ?: input.rightVariantId else null,
        cheeseId = effCheeseId,
        cheeseName = cheese?.name,
        extraCheese = input.extraCheese,
        sauceId = effSauceId,
        sauceName = leftSauce?.name,
        rightSauceId = if (input.isHalfAndHalf) effRightSauceId else null,
        rightSauceName = if (input.isHalfAndHalf) rightSauce?.name else null,
        extraSauce = input.extraSauce,
        removedIncludedToppings = removedIncludedNames,
        configurationSignature = signature,
      )

    return PizzaBuilderEvaluation(
      isValid = issues.isEmpty(),
      issues = issues,
      priceBreakdown = priceBreakdown,
      unitPricePaise = unitPricePaise,
      lineTotalPaise = lineTotalPaise,
      toppings = toppingViews,
      customization = customization,
      configurationSignature = signature,
      effectiveSizeCode = leftVariant?.sizeCode,
      effectiveCheeseId = effCheeseId,
      effectiveSauceId = effSauceId,
      effectiveRightSauceId = effRightSauceId,
    )
  }

  private fun defaultSizeRule(sizeCode: SizeCode): PizzaSizeRuleDto =
    when (sizeCode) {
      SizeCode.REGULAR ->
        PizzaSizeRuleDto(
          sizeCode = SizeCode.REGULAR,
          displayName = "Regular",
          maxToppings = 5,
          maxExtraToppings = 3,
          supportsHalfAndHalf = false,
          ingredientMultiplierBps = 7_500,
          isActive = true,
        )
      SizeCode.MEDIUM ->
        PizzaSizeRuleDto(
          sizeCode = SizeCode.MEDIUM,
          displayName = "Medium",
          maxToppings = 7,
          maxExtraToppings = 4,
          supportsHalfAndHalf = true,
          ingredientMultiplierBps = 10_000,
          isActive = true,
        )
      SizeCode.LARGE ->
        PizzaSizeRuleDto(
          sizeCode = SizeCode.LARGE,
          displayName = "Large",
          maxToppings = 9,
          maxExtraToppings = 5,
          supportsHalfAndHalf = true,
          ingredientMultiplierBps = 13_500,
          isActive = true,
        )
      SizeCode.EXTRA_LARGE ->
        PizzaSizeRuleDto(
          sizeCode = SizeCode.EXTRA_LARGE,
          displayName = "Extra Large",
          maxToppings = 11,
          maxExtraToppings = 6,
          supportsHalfAndHalf = true,
          ingredientMultiplierBps = 17_000,
          isActive = true,
        )
    }
}
