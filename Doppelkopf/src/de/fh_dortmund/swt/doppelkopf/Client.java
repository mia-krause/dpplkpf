package de.fh_dortmund.swt.doppelkopf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Scanner;

import javax.persistence.*;

import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import de.fh_dortmund.swt.doppelkopf.enumerations.CardColour;
import de.fh_dortmund.swt.doppelkopf.enumerations.CardValue;
import de.fh_dortmund.swt.doppelkopf.enumerations.Suit;
import de.fh_dortmund.swt.doppelkopf.interfaces.Message;
import de.fh_dortmund.swt.doppelkopf.messages.ClientMqttCallback;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_AddCardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_LastTrickMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_LeaderBoardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_LoginReactionMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_NextPlayerMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_PlayedCardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_StateMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_LoginMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_LogoutMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_PlayedCardMsg;

/**
 * Manages player input as well as server communication
 */
public class Client implements Serializable{

	private static final long serialVersionUID = -3724342480704062462L;

	private static final Logger logger = Logger.getLogger(Client.class);

	private transient boolean loggedIn = false;
	private boolean re = false;
	private transient ArrayList<Card> cards = new ArrayList<>();
	private transient MqttClient mqttClient;

	private Player me;
	private transient Scanner keyboard = new Scanner(System.in);
	private final String id = System.nanoTime() + System.getProperty("user.name");
	private transient Trick currentTrick;

	/**
	 * Lifecycle:
	 * 1. Connects to MQTT Broker
	 * 2. Logs in
	 * 3. Waits for server reaction / successful login
	 * 4. While logged in: Waits for messages
	 */
	public static void main(String[] args) {
		logger.info("Running");
		Client instance = new Client();
		instance.connect();
		instance.showLoginPrompt();
		while(instance.getPlayer()==null) { //player will be set on successful login message in Callback Class
			try { Thread.sleep(100); } catch (InterruptedException e) { }
		}
		instance.setLoggedIn(true);
		while(instance.isLoggedIn()) {
			//TODO
		}
	}
	public Client()
	{
		
	}
	public  ArrayList<Card> getCards() {
		return cards;
	}
	/** Sets re flag */
	public  void setCards(ArrayList<Card> cards) {
		Card clubsQueen = new Card(CardColour.CLUB, CardValue.QUEEN);
		if(cards.contains(clubsQueen)) setRe(true);
		this.cards = cards;
	}
	/** Sets re flag */
	public  void addCard(Card card) {
		Card clubsQueen = new Card(CardColour.CLUB, CardValue.QUEEN);
		if(card.equals(clubsQueen)) setRe(true);
		cards.add(card);
	}


	/** 
	 * Shows card choice prompts, removes card after valid card input and notifies the server via ToServer_PlayedCardMsg
	 */
	public  Card chooseCard() {
		logger.info("It's your turn!");
		if(currentTrick != null) logger.info(currentTrick.toString());
		int pos = 1;
		String hand = "Your cards: ";
		for (Card card : cards) {
			hand += pos + ": " + card.toString() + "    ";
			pos++;
		}
		logger.info(hand);
		boolean validCard = false;
		int input = 0;
		Card card = null;
		try { Thread.sleep(500); } catch (InterruptedException e1) { } //For convenience - focuses on this clients console
		while(!validCard) {
			logger.info("-> Enter card position:");
			try{
				input = keyboard.nextInt();
				if(cards.size()>=input && input>0) {
					card = cards.get(input-1);
					validCard = checkSuitToFollow(card);
					if(!validCard) logger.info("X  Your card sucks!!! ");
				}
				else {
					logger.info("X  Are you even capable of counting?!");
				}
			} catch(Exception e) {
				logger.info("X  Maybe you should go back to preschool and learn, what a number is...");
				keyboard.next(); //important, else line won't be cleared and thread will be caught in error loop
			}
		}
		cards.remove(input-1);
		publishMessage(new ToServer_PlayedCardMsg(this, card));
		return card;
	}

	/**
	 * Publishes an Message to its topic, serializing it into an byte[] 
	 */
	public  void publishMessage(Message msg) {
		MqttMessage message = new MqttMessage();

		try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); ObjectOutput out = new ObjectOutputStream(bos);){
			out.writeObject(msg);
			out.flush();
			byte[] bytes = bos.toByteArray();
			message.setPayload(bytes);
			mqttClient.publish(msg.getType(), message);
			
			Thread.sleep(100);
		} catch (IOException e) {
			logger.error("Could not serialize Message '" + msg.getMessage() + "': " + e.getMessage());
		} catch (MqttException e) {
			logger.error("Problems while publishing Message '" + msg.getMessage() + "':" + e.getMessage());
		} catch (InterruptedException e) {
			logger.error("Thread won't go to sleep!");
		}
	}

	public boolean login() {
		showLoginPrompt();
		return true;
	}

	/**
	 * Notifies Server of logout attempt via ToServer_LogoutMsg, disconnects from MQTT Broker afterwards
	 */
	public void logout() {
		publishMessage(new ToServer_LogoutMsg(this));
		try {
			mqttClient.disconnect();
		} catch (MqttException e) { }
		keyboard.close();
	}

	/**
	 * Prompts input lines and sends them to server via ToServer_LoginMsg
	 */
	public void showLoginPrompt() {

		String usernameAttempt = null; 
		logger.info("-> Please enter your username: ");
		while(usernameAttempt==null)
			usernameAttempt = keyboard.nextLine();

		String pwAttempt = null;
		while(pwAttempt ==null) {
			logger.info("-> Please enter your password: ");
			pwAttempt = keyboard.nextLine();
		}
		publishMessage(new ToServer_LoginMsg(this, usernameAttempt, pwAttempt));
	}

	public  void showLogoutPrompt() {
		String logout = "";
		logger.info("-> Do you want to logout? [y/n]");
		while(logout.equals("y") && logout.equals("n")) {
			logout = keyboard.next();
		}
		if(logout.equals("y")) logout();
	}


	/**
	 * Checks, if client could and does follow a possible suit. If no trick is given, Client will see this as an
	 * indicator, that he is the first in line, so he's free to pick a suit 
	 */
	public  boolean checkSuitToFollow(Card cardToCheck) 
	{
		if(currentTrick==null) return true;

		Suit suit = currentTrick.getSuitToFollow();

		if(cardToCheck.getSuit().equals(suit)) return true;

		else {
			for (Card card : cards) {
				if(suit.equals(card.getSuit()))
					return false;
			}
			return true;
		}
	}

	/**
	 * Connects to MQTT Broker and subscribes to topics
	 */
	public void connect() {
		try {
			mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId());
			mqttClient.connect();
			mqttClient.setTimeToWait(100000);
			mqttClient.setCallback(new ClientMqttCallback(this));
			mqttClient.subscribe(ToClient_AddCardMsg.type);
			mqttClient.subscribe(ToClient_LastTrickMsg.type);
			mqttClient.subscribe(ToClient_LeaderBoardMsg.type);
			mqttClient.subscribe(ToClient_LoginReactionMsg.type);
			mqttClient.subscribe(ToClient_NextPlayerMsg.type);
			mqttClient.subscribe(ToClient_PlayedCardMsg.type);
			mqttClient.subscribe(ToClient_StateMsg.type);
		} catch (MqttException e) {
			logger.error("Could not connect to MQTT Broker (" + e.getMessage() + ")");
		}
	}

	public Player getPlayer() {
		return me;
	}
	public void setPlayer(Player player) {
		me = player;
	}

	public boolean isLoggedIn() {
		return loggedIn;
	}
	public void setLoggedIn(boolean loggedIn) {
		this.loggedIn = loggedIn;
	}

	public String getId() {
		return id;
	}

	public boolean isRe() {
		return re;
	}
	public void setRe(boolean re) {
		this.re = re;
	}

	public Trick getCurrentTrick() {
		return currentTrick;
	}

	public void setCurrentTrick(Trick currentTrick) {
		this.currentTrick = currentTrick;
	}

	@Override
	/**
	 * Only evaluates equality by id
	 */
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Client other = (Client) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		return true;
	}

}
