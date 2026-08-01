package com.thecardexchange.tcg.trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A message pushed by the broker over the trade WebSocket.
 *
 * <ul>
 *   <li>{@code OFFERED} — ack that our offer is now pending ({@link #getOther()} is the target).</li>
 *   <li>{@code INCOMING} — someone offered us a trade ({@link #getOther()} is them).</li>
 *   <li>{@code ACCEPTED} — the trade is on; open the dialogue.</li>
 *   <li>{@code DECLINED} / {@code CANCELLED} — the other side backed out; close the dialogue.</li>
 *   <li>{@code CARDS} — a side's offer changed: {@link #getOther()} is whose it is and
 *       {@link #getCardIds()} is what they're putting up, already validated server-side.</li>
 *   <li>{@code TRADE_ACCEPTED} — {@link #getOther()} accepted the cards on the table.</li>
 *   <li>{@code COMPLETED} — both accepted and the cards moved; {@link #getCardIds()} is what we got.</li>
 *   <li>{@code ERROR} — the action failed ({@link #getReason()}, e.g. "expired").</li>
 * </ul>
 */
public final class TradeEvent
{
	public enum Type
	{
		OFFERED, INCOMING, ACCEPTED, DECLINED, CANCELLED, CARDS, TRADE_ACCEPTED, COMPLETED, ERROR, UNKNOWN
	}

	private final Type type;
	@Nullable
	private final String offerId;
	/** The other player's display name (from `with` / `from` / `to`, whichever the message carried). */
	@Nullable
	private final String other;
	@Nullable
	private final String reason;
	/**
	 * The server's own words for a refusal, when it sent any.
	 *
	 * <p>Worth carrying rather than re-deriving: the server knows *why* it refused — a mode not
	 * chosen, a mode mismatch, a character under review — and a generic "could not be completed"
	 * leaves the player with nothing to act on. `reason` stays the machine-readable code the switch
	 * branches on; this is what gets shown.
	 */
	@Nullable
	private final String message;
	/** The cards on the table for {@code CARDS}; empty otherwise. */
	private final List<Integer> cardIds;

	public TradeEvent(Type type, @Nullable String offerId, @Nullable String other, @Nullable String reason)
	{
		this(type, offerId, other, reason, null, Collections.emptyList());
	}

	public TradeEvent(Type type, @Nullable String offerId, @Nullable String other, @Nullable String reason,
		List<Integer> cardIds)
	{
		this(type, offerId, other, reason, null, cardIds);
	}

	public TradeEvent(Type type, @Nullable String offerId, @Nullable String other, @Nullable String reason,
		@Nullable String message, List<Integer> cardIds)
	{
		this.type = type;
		this.offerId = offerId;
		this.other = other;
		this.reason = reason;
		this.message = message;
		this.cardIds = Collections.unmodifiableList(new ArrayList<>(cardIds));
	}

	@Nullable
	public String getMessage()
	{
		return message;
	}

	public List<Integer> getCardIds()
	{
		return cardIds;
	}

	public Type getType()
	{
		return type;
	}

	@Nullable
	public String getOfferId()
	{
		return offerId;
	}

	@Nullable
	public String getOther()
	{
		return other;
	}

	@Nullable
	public String getReason()
	{
		return reason;
	}
}
