package com.handsoffretail.verifone.parse.model;

import java.util.Objects;
import java.util.Optional;

/**
 * The store's codes as a file names them (natural keys + the name printed with them). The loader
 * turns each into a {@code master.*} code / version id.
 */
public final class Codes {

    private Codes() {
    }

    /** trlDept: @number, @type (norm / neg / fuel), text = name. */
    public record Department(int number, String name, String type) {
        public Department {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /** trlCat: @number, text = name. */
    public record Category(int number, String name) {
        public Category {
            Objects.requireNonNull(name, "name");
        }
    }

    /** trlUPC (leading zeros kept) + trlModifier + trlDesc. */
    public record Item(String upc, String modifier, String description) {
        public Item {
            Objects.requireNonNull(upc, "upc");
            Objects.requireNonNull(modifier, "modifier");
            Objects.requireNonNull(description, "description");
        }
    }

    /** cashier / originalCashier: @sysid, text = name, @empNum (a number, or a login name on some registers; as written). */
    public record Cashier(int sysid, String name, Optional<String> employeeNumber) {
        public Cashier {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(employeeNumber, "employeeNumber");
        }
    }

    /** A tax by @sysid with its @cat name (STATE). */
    public record Tax(int sysid, String name) {
        public Tax {
            Objects.requireNonNull(name, "name");
        }
    }

    /** trpPaycode: code = @mop + text (CASH and Change share @mop 1); version = @cat, NACS codes. */
    public record PaymentType(int mop, String paycode, int category, String nacsTenderCode, String nacsTenderSub) {
        public PaymentType {
            Objects.requireNonNull(paycode, "paycode");
            Objects.requireNonNull(nacsTenderCode, "nacsTenderCode");
            Objects.requireNonNull(nacsTenderSub, "nacsTenderSub");
        }
    }

    /** fuelProd: @sysid, text = name, @NAXMLFuelGradeID. */
    public record FuelGrade(int sysid, String name, Optional<String> naxmlGradeId) {
        public FuelGrade {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(naxmlGradeId, "naxmlGradeId");
        }
    }

    /** A fuel code with a name: fuelMOP (price level) or fuelSvcMode (service mode). */
    public record NamedCode(int sysid, String name) {
        public NamedCode {
            Objects.requireNonNull(name, "name");
        }
    }

    /** trlPromotionID text = code, @promotype; trlMatchName = name. */
    public record Promotion(String code, String name, String promoType) {
        public Promotion {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(promoType, "promoType");
        }
    }

    /** trHouseAcct: text = account number, @name. */
    public record HouseAccount(String accountNumber, String name) {
        public HouseAccount {
            Objects.requireNonNull(accountNumber, "accountNumber");
            Objects.requireNonNull(name, "name");
        }
    }
}
