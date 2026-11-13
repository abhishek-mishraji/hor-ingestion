package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code <trLine>} of a ticket or of an ERROR CORRECT cashier event. {@code posDescription} is
 * trlDesc only when it is neither the item description nor the department name.
 */
public record Line(
        int lineNo,
        String lineType,
        boolean duplicate,
        boolean unsettled,
        Codes.Department department,
        Optional<Codes.Category> category,
        Optional<Codes.Item> item,
        Optional<String> upcEntryMethod,
        String networkCode,
        BigDecimal quantity,
        int sign,
        Optional<BigDecimal> sellUnit,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        Optional<BigDecimal> priceOverride,
        Optional<Discount> discount,
        Optional<Integer> voidedLineNo,
        Optional<String> posDescription,
        List<Flag> flags,
        Optional<Fuel> fuel,
        List<FuelDiscount> fuelDiscounts,
        List<LoyaltyDiscount> loyaltyDiscounts,
        List<Promotion> promotions,
        List<Tax> taxes) {

    public Line {
        Objects.requireNonNull(lineType, "lineType");
        Objects.requireNonNull(department, "department");
        Objects.requireNonNull(networkCode, "networkCode");
        flags = List.copyOf(flags);
        fuelDiscounts = List.copyOf(fuelDiscounts);
        loyaltyDiscounts = List.copyOf(loyaltyDiscounts);
        promotions = List.copyOf(promotions);
        taxes = List.copyOf(taxes);
    }

    /** trlDisc: @type (pct) and value. */
    public record Discount(String type, BigDecimal value) {
    }

    /** One child of trlFlags; only trlNegative has a @cause. */
    public record Flag(String code, Optional<String> cause) {
    }

    /** trlFuel of a postFuel line. */
    public record Fuel(String fuelType, Codes.FuelGrade grade, Codes.NamedCode priceLevel, Codes.NamedCode serviceMode,
            int position, BigDecimal volume, String volumeUom, BigDecimal basePrice, Optional<BigDecimal> deposit,
            Optional<Long> fuelSeq) {
    }

    /** trloPPGDisc: loyalty cents-per-gallon discount, one per loyalty program. */
    public record FuelDiscount(String programCode, BigDecimal perGallon, BigDecimal originalPrice, BigDecimal total,
            BigDecimal volume) {
    }

    /** trloLnItemDisc: loyalty discount on an item line. */
    public record LoyaltyDiscount(String programCode, BigDecimal amount, BigDecimal quantity, BigDecimal taxCredit) {
    }

    /** trlMixMatches/trlMatchLine. */
    public record Promotion(int matchNo, Codes.Promotion promotion, int mixes, BigDecimal quantity, BigDecimal price,
            BigDecimal promoAmount) {
    }

    /** trlTaxes: trlTax + trlRate of the same @sysid. */
    public record Tax(Codes.Tax tax, BigDecimal amount, BigDecimal rate, boolean reverse) {
    }
}
