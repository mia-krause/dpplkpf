package de.fh_dortmund.swt.doppelkopf.dataexcange;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

public class GameOverDataModel {
	private PropertyChangeSupport propertyChangeSupport;
	
	public GameOverDataModel() {
		propertyChangeSupport = new PropertyChangeSupport(this);
	}
	
	public void setBackToMenuPressed() {
		propertyChangeSupport.firePropertyChange("BackToMenuProperty", false, true);
	}
	
	public void setNextGamePressed() {
		propertyChangeSupport.firePropertyChange("NextGameProperty", false, true);
	}
	
	public void registerPropertyChangeListener(PropertyChangeListener listener) {
		propertyChangeSupport.addPropertyChangeListener(listener);
	}

	public void setGameScore(String gameScore) {
		propertyChangeSupport.firePropertyChange("GameScoreProperty", null, gameScore);
	}
}
