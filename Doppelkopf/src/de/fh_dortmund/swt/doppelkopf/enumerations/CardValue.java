package de.fh_dortmund.swt.doppelkopf.enumerations;

import java.io.Serializable;

/**
 * Actual value of a card
 */
public enum CardValue implements Serializable {

	TEN("10", 10),
	JACK("J", 2),
	QUEEN("Q", 3),
	KING("K", 4),
	ACE("A", 11);
	
	int points;
	String initial;
	
	CardValue(String initial, int points) {
		this.initial = initial;
		this.points = points;
	}

	public int getPoints() {
		return points;
	}

	public void setPoints(int value) {
		this.points = value;
	}
	
	@Override
	public String toString() {
		return initial;
	}
	
	
}
