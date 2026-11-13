package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Line;

/** What the trLine handlers found, before the line rules are applied in {@link LineParser}. */
final class LineDraft {

    final String path;
    Codes.Department department;
    Codes.Category category;
    String networkCode;
    BigDecimal quantity;
    Integer sign;
    BigDecimal sellUnit;
    BigDecimal unitPrice;
    BigDecimal lineTotal;
    String description;
    String upc;
    String modifier;
    String upcEntryMethod;
    Integer voidedLineNo;
    BigDecimal priceOverride;
    Line.Discount discount;
    final List<Line.Flag> flags = new ArrayList<>();
    Line.Fuel fuel;
    final List<Line.FuelDiscount> fuelDiscounts = new ArrayList<>();
    final List<Line.LoyaltyDiscount> loyaltyDiscounts = new ArrayList<>();
    final List<Line.Promotion> promotions = new ArrayList<>();
    final List<Line.Tax> taxes = new ArrayList<>();

    LineDraft(String path) {
        this.path = path;
    }
}
