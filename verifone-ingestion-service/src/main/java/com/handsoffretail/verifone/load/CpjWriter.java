package com.handsoffretail.verifone.load;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.model.CashierEvent;
import com.handsoffretail.verifone.parse.model.CashierSession;
import com.handsoffretail.verifone.parse.model.CpjFileHeader;
import com.handsoffretail.verifone.parse.model.CpjFileTrailer;
import com.handsoffretail.verifone.parse.model.CpjSink;
import com.handsoffretail.verifone.parse.model.FileTotals;
import com.handsoffretail.verifone.parse.model.Line;
import com.handsoffretail.verifone.parse.model.Payment;
import com.handsoffretail.verifone.parse.model.PeriodClose;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * Writes one CPJ file into the cpj tables, inside the caller's load transaction: the day row first,
 * then tickets, events and their children in batches, then the end-of-file records. Master codes
 * come from the {@link MasterCodeResolver}; ids from the {@link IdAllocator}.
 */
final class CpjWriter implements CpjSink {

    private final JdbcTemplate jdbc;
    private final IdAllocator ids;
    private final MasterCodeResolver codes;
    private final Set<String> flagCodes;
    private final Target target;
    private final Clock clock;
    private final Batches batches;
    private final Map<Integer, Integer> physicalRegisterIds = new LinkedHashMap<>();
    private final List<String> storedRegisterConflicts = new ArrayList<>();
    private long cpjFileId;

    CpjWriter(JdbcTemplate jdbc, IdAllocator ids, MasterCodeResolver codes, Set<String> flagCodes, Target target,
            Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.codes = codes;
        this.flagCodes = flagCodes;
        this.target = target;
        this.clock = clock;
        this.batches = new Batches(jdbc);
    }

    /** Where this file goes: store, day, zip and fetch it belongs to, and its path in the zip. */
    record Target(long storeId, LocalDate businessDate, int daySeq, long s3ObjectId, String filePath, long fetchRequestId) {
    }

    long cpjFileId() {
        return cpjFileId;
    }

    List<String> storedRegisterConflicts() {
        return List.copyOf(storedRegisterConflicts);
    }

    Map<String, Integer> rowsWritten() {
        return batches.written();
    }

    // ---- sink --------------------------------------------------------------------------------

    @Override
    public void begin(CpjFileHeader h) {
        if (h.daySeq() != target.daySeq()) {
            throw new CpjFormatException("transSet/@shortId " + h.daySeq() + " differs from the number in the file name "
                    + target.filePath());
        }
        cpjFileId = ids.next("cpj.cpj_files", "cpj_file_id");
        jdbc.update("""
                INSERT INTO cpj.cpj_files (cpj_file_id, store_id, business_date, day_seq, s3_object_id, file_path,
                    fetch_request_id, period_id, period_name, period_long_id, site_id, opened_at, closed_at, loaded_at)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, cpjFileId, target.storeId(), target.businessDate(), h.daySeq(), target.s3ObjectId(), target.filePath(),
                target.fetchRequestId(), h.periodId(), h.periodName(), h.periodLongId(), h.siteId(), h.openedAt(),
                h.closedAt(), OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)));
        totals(h.startTotals());
    }

    @Override
    public void ticket(Ticket t) {
        long ticketId = ids.next("cpj.tickets", "ticket_id");
        Ticket.Header h = t.header();
        long register = codes.register(t.posNum());
        t.physicalRegisterId().ifPresent(p -> physicalRegisterIds.putIfAbsent(t.posNum(), p));
        Optional<Ticket.Value> v = t.value();
        batches.add(CpjTables.TICKETS, ticketId, cpjFileId, t.seqInFile(), t.transType(), t.flags().suspended(),
                t.flags().fuelPrepay(), t.flags().rollback(), t.flags().prepayCompletion(), t.flags().recalled(), register,
                v(h.trSeq()), v(h.termMsgType()), v(h.terminalNo()), v(h.termMsgSn()), v(h.uniqueId()), v(h.uniqueSn()),
                h.at(), v(h.durationSec()), v(h.hourSeq()), v(h.shiftSeq()), v(h.daySeq()), v(h.till()),
                v(h.original().map(o -> codes.register(o.posNum()))), v(h.original().map(Ticket.OriginalTicket::trSeq)),
                v(h.recall().map(Ticket.Recall::transType)), v(h.recall().map(r -> codes.register(r.posNum()))),
                v(h.recall().map(Ticket.Recall::trSeq)), v(h.miscRef()), v(h.houseAccount().map(codes::houseAccountVersion)),
                h.fuelOnlyCustomer(), v(v.flatMap(Ticket.Value::currency)), v(v.flatMap(Ticket.Value::total)),
                v(v.flatMap(Ticket.Value::totalNoTax)), v(v.flatMap(Ticket.Value::totalTax)),
                v(v.flatMap(Ticket.Value::totalWithTax)), v(v.flatMap(Ticket.Value::sTotalizer)),
                v(v.flatMap(Ticket.Value::gTotalizer)), v(v.flatMap(Ticket.Value::recallAmount)));
        for (Ticket.Cashier c : t.cashiers()) {
            batches.add(CpjTables.TICKET_CASHIERS, ticketId, c.role(), codes.cashierVersion(c.cashier()),
                    codes.register(c.posNum()), c.period(), c.drawer());
        }
        v.ifPresent(value -> ticketValue(ticketId, value));
        t.coupons().forEach(c -> batches.add(CpjTables.TICKET_COUPONS, ticketId, c.couponNo(), c.discount(),
                c.affectsTax(), c.entryMethod(), v(c.couponData())));
        t.networkProducts().forEach(n -> batches.add(CpjTables.TICKET_NETWORK_PRODUCTS, ticketId, n.entryNo(),
                n.productCode(), n.itemCount(), n.amount()));
        for (Ticket.Loyalty l : t.loyalty()) {
            long program = codes.loyaltyProgram(l.programCode());
            batches.add(CpjTables.TICKET_LOYALTY, ticketId, program, v(codes.loyaltyAccount(program, l.account())),
                    v(l.authReply()), v(l.autoDiscount()), v(l.customerDiscount()), v(l.entryMethod()), v(l.subtotal()));
        }
        t.payments().forEach(p -> payment(ticketId, p));
        String where = "transaction #" + t.seqInFile();
        t.lines().forEach(line -> line(ticketId, null, line, where));
        t.priceChange().ifPresent(pc -> priceChange(ticketId, pc));
    }

    @Override
    public void cashierEvent(CashierEvent e) {
        long eventId = ids.next("cpj.cashier_events", "cashier_event_id");
        Optional<CashierEvent.QuantityChange> q = e.quantityChange();
        batches.add(CpjTables.CASHIER_EVENTS, eventId, cpjFileId, e.seqInFile(), e.eventType(), e.at(),
                codes.register(e.posNum()), v(e.cashier().map(codes::cashierVersion)), v(e.cashierEntryMode()),
                v(e.termMsgSn()), v(e.uniqueId()), e.text(), v(q.map(CashierEvent.QuantityChange::itemDescription)),
                v(q.map(CashierEvent.QuantityChange::from)), v(q.map(CashierEvent.QuantityChange::to)), v(e.durationSec()),
                v(e.hourSeq()), v(e.shiftSeq()), v(e.daySeq()), v(e.termMsgType()), v(e.terminalNo()),
                v(e.cashierPeriod()), v(e.drawer()));
        e.drawerTotals().forEach(d -> batches.add(CpjTables.EVENT_DRAWER_TOTALS, eventId, d.tenderName(), d.amount()));
        e.priceCheck().ifPresent(pc -> batches.add(CpjTables.EVENT_PRICE_CHECKS, eventId, codes.itemVersion(pc.item()),
                pc.quantity(), pc.unitPrice(), pc.lineTotal()));
        String where = "cashier event #" + e.seqInFile();
        e.lines().forEach(line -> line(null, eventId, line, where));
    }

    @Override
    public void end(CpjFileTrailer trailer) {
        totals(trailer.endTotals());
        trailer.sessions().forEach(this::session);
        trailer.periodCloses().forEach(this::periodClose);
        batches.flush();
        physicalRegisterIds.forEach(this::physicalRegister);
    }

    // ---- children ----------------------------------------------------------------------------

    private void ticketValue(long ticketId, Ticket.Value value) {
        value.foodStamp().ifPresent(f -> batches.add(CpjTables.TICKET_FOOD_STAMPS, ticketId, f.total(), f.tax(),
                f.change(), f.tendered()));
        value.ageCheck().ifPresent(a -> batches.add(CpjTables.TICKET_AGE_CHECKS, ticketId, a.birthDate(),
                a.customerAge(), a.minAge(), a.entryMethod()));
        for (Ticket.Tax tax : value.taxes()) {
            batches.add(CpjTables.TICKET_TAXES, ticketId, codes.taxVersion(tax.tax()), tax.refund(), tax.taxableAmount(),
                    tax.rate(), tax.amount(), tax.attribute(), v(tax.exempt()), v(tax.exemptAgainst()));
        }
    }

    private void payment(long ticketId, Payment p) {
        long paymentId = ids.next("cpj.payment_lines", "payment_line_id");
        batches.add(CpjTables.PAYMENT_LINES, paymentId, ticketId, p.lineNo(), p.type(),
                codes.paymentTypeVersion(p.paymentType()), p.amount(), v(p.currency()), v(p.posSysid()), p.duplicate(),
                p.pending(), v(p.originalPreauth()), v(p.couponEntryMethod()),
                v(p.houseAccount().map(codes::houseAccountVersion)));
        p.card().ifPresent(c -> batches.add(CpjTables.PAYMENT_CARD_DETAILS, paymentId, codes.card(c.maskedNumber()),
                codes.cardType(c.cardName()), v(c.cardNameSysid()), c.entryMethod(), v(c.authCode()), v(c.authAt()),
                v(c.authSource()), v(c.batchNo()), v(c.acquirerBatchNo()), v(c.hostId()), v(c.referenceNo()),
                v(c.sequenceNo()), v(c.hostTicket()), v(c.aboveSite())));
    }

    private void line(Long ticketId, Long eventId, Line l, String where) {
        long lineId = ids.next("cpj.ticket_lines", "line_id");
        batches.add(CpjTables.TICKET_LINES, lineId, ticketId, eventId, l.lineNo(), l.lineType(), l.duplicate(),
                l.unsettled(), codes.departmentVersion(l.department()), v(l.category().map(codes::categoryVersion)),
                v(l.item().map(codes::itemVersion)), v(l.upcEntryMethod()), l.networkCode(), l.quantity(), l.sign(),
                v(l.sellUnit()), l.unitPrice(), l.lineTotal(), v(l.priceOverride()),
                v(l.discount().map(Line.Discount::type)), v(l.discount().map(Line.Discount::value)), v(l.voidedLineNo()),
                v(l.posDescription()));
        for (Line.Flag flag : l.flags()) {
            if (!flagCodes.contains(flag.code())) {
                throw new CpjFormatException("unknown line flag " + flag.code() + " (" + where + ", line " + l.lineNo()
                        + "); add it to cpj.line_flag_types with a new migration once its meaning is known");
            }
            batches.add(CpjTables.LINE_FLAGS, lineId, flag.code(), v(flag.cause()));
        }
        l.fuel().ifPresent(f -> batches.add(CpjTables.LINE_FUEL, lineId, f.fuelType(), codes.fuelGradeVersion(f.grade()),
                codes.priceLevelVersion(f.priceLevel()), codes.serviceModeVersion(f.serviceMode()), f.position(), f.volume(),
                f.volumeUom(), f.basePrice(), v(f.deposit()), v(f.fuelSeq())));
        l.fuelDiscounts().forEach(d -> batches.add(CpjTables.LINE_FUEL_DISCOUNTS, lineId, codes.loyaltyProgram(d.programCode()),
                d.perGallon(), d.originalPrice(), d.total(), d.volume()));
        l.loyaltyDiscounts().forEach(d -> batches.add(CpjTables.LINE_LOYALTY_DISCOUNTS, lineId,
                codes.loyaltyProgram(d.programCode()), d.amount(), d.quantity(), d.taxCredit()));
        l.promotions().forEach(p -> batches.add(CpjTables.LINE_PROMOTIONS, lineId, p.matchNo(),
                codes.promotionVersion(p.promotion()), p.mixes(), p.quantity(), p.price(), p.promoAmount()));
        l.taxes().forEach(x -> batches.add(CpjTables.LINE_TAXES, lineId, codes.taxVersion(x.tax()), x.amount(), x.rate(),
                x.reverse()));
    }

    private void priceChange(long ticketId, Ticket.PriceChange pc) {
        batches.add(CpjTables.FUEL_PRICE_CHANGES, ticketId, pc.changedBy(), v(pc.origin()));
        for (Ticket.PriceChangeEntry e : pc.entries()) {
            batches.add(CpjTables.FUEL_PRICE_CHANGE_LINES, ticketId, e.entryNo(), v(e.infoSysid()),
                    codes.fuelGrade(e.gradeSysid()), codes.serviceMode(e.serviceModeSysid()),
                    codes.priceLevel(e.priceLevelSysid()), codes.fuelTier(e.tierSysid()), e.previousPrice(),
                    v(e.previousSysid()), e.currentPrice(), v(e.currentSysid()));
        }
    }

    private void totals(FileTotals t) {
        batches.add(CpjTables.FILE_TOTALS, cpjFileId, t.kind().name(), t.insideSales(), t.insideGrand(), t.outsideSales(),
                t.outsideGrand(), t.attendantInsideSales(), t.attendantInsideGrand(), t.attendantOutsideSales(),
                t.attendantOutsideGrand(), t.overallSales(), t.overallGrand());
    }

    private void session(CashierSession s) {
        Optional<CashierSession.BeginCash> b = s.beginCash();
        Optional<CashierSession.Close> c = s.close();
        batches.add(CpjTables.CASHIER_SESSIONS, cpjFileId, codes.register(s.posNum()), codes.cashierVersion(s.cashier()),
                s.cashierPeriod(), s.drawer(), s.openedAt(), v(b.map(CashierSession.BeginCash::amount)),
                v(b.map(CashierSession.BeginCash::mopCode)), v(b.map(CashierSession.BeginCash::mopCategory)),
                v(c.map(CashierSession.Close::closedAt)), v(c.map(CashierSession.Close::beginCash)),
                v(c.map(CashierSession.Close::beginFoodStamp)), v(c.map(CashierSession.Close::beginTicket)),
                v(c.map(CashierSession.Close::endTicket)), v(c.map(CashierSession.Close::ticketCount)),
                v(c.flatMap(CashierSession.Close::mwsEnabled)));
    }

    private void periodClose(PeriodClose p) {
        batches.add(CpjTables.PERIOD_CLOSES, cpjFileId, p.periodType(), p.periodSeq(), p.periodName(), p.closedAt(),
                p.uniqueId(), v(p.mwsEnabled()));
    }

    /**
     * physicalRegisterID is a fact of the register (it equalled posNum on every sample ticket): stored
     * once; a stored value that differs from the file is a register-identity failure.
     */
    private void physicalRegister(int posNum, int physicalId) {
        long registerId = codes.register(posNum);
        jdbc.update("UPDATE master.registers SET physical_register_id = ? WHERE register_id = ? AND physical_register_id IS NULL",
                physicalId, registerId);
        Integer stored = jdbc.queryForObject("SELECT physical_register_id FROM master.registers WHERE register_id = ?",
                Integer.class, registerId);
        if (stored != null && stored != physicalId) {
            storedRegisterConflicts.add("register " + posNum + " is stored with physical id " + stored + ", the file says "
                    + physicalId);
        }
    }

    private static Object v(Optional<?> value) {
        return value.orElse(null);
    }
}
