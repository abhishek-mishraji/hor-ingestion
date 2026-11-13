package com.handsoffretail.verifone.parse.model;

/**
 * Receives a CPJ file in document order, one record at a time (the parser never holds more than one
 * {@code <trans>}). {@link #begin} comes before any ticket or event; {@link #end} comes last.
 */
public interface CpjSink {

    void begin(CpjFileHeader header);

    void ticket(Ticket ticket);

    void cashierEvent(CashierEvent event);

    void end(CpjFileTrailer trailer);
}
