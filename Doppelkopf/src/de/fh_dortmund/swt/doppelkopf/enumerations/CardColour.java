package de.fh_dortmund.swt.doppelkopf.enumerations;

public enum CardColour {

	CLUB("♣"),
	SPADE("♠"),
	HEART("♥"),
	DIAMOND("♦");
	
	private String initial;
	
	private CardColour(String initial) {
		this.initial = initial;
	}
	
	@Override
	public String toString() {
		return initial;
	}
	
	public Suit toSuit() {
		if(this.equals(CLUB)) return Suit.CLUB;
		if(this.equals(SPADE)) return Suit.SPADE;
		if(this.equals(HEART)) return Suit.HEART;
		return Suit.DIAMOND;
	}
	
}
