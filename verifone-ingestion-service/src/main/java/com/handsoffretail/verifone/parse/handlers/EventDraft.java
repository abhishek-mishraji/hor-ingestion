package com.handsoffretail.verifone.parse.handlers;

import java.util.ArrayList;
import java.util.List;

import com.handsoffretail.verifone.parse.model.CashierEvent;
import com.handsoffretail.verifone.parse.model.Line;

/** What the trJournal handlers of a kept cashier event found. */
final class EventDraft {

    String textType;
    String text;
    final List<Line> lines = new ArrayList<>();
    final List<CashierEvent.DrawerTotal> drawerTotals = new ArrayList<>();
    CashierEvent.PriceCheck priceCheck;
}
