package de.fh_dortmund.swt.doppelkopf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.persistence.NoResultException;

import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import de.fh_dortmund.swt.doppelkopf.enumerations.CardColour;
import de.fh_dortmund.swt.doppelkopf.enumerations.CardValue;
import de.fh_dortmund.swt.doppelkopf.enumerations.State;
import de.fh_dortmund.swt.doppelkopf.interfaces.Message;
import de.fh_dortmund.swt.doppelkopf.messages.GameManagerMqttCallback;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_AddCardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_LeaderBoardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_LoginReactionMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_NextPlayerMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_OverallScoreMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_PlayedCardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToClient_StateMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_EnterLobbyMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_LeaderBoardMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_LeaveLobbyMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_LoginMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_LogoutMsg;
import de.fh_dortmund.swt.doppelkopf.messages.ToServer_PlayedCardMsg;

/** 
 * Manages game as well as client communication
 *
 */
public class GameManager {
	private static final Logger logger = Logger.getLogger(GameManager.class);
	private Game game;
	private Client[] clients = new Client[4];
	private static boolean waitingForPlayedCard = false;
	private static int loggedInClients = 0;
	transient private MqttClient mqttClient;
	private static int clientsInLobby = 0;

	/** 
	 * Lifecyle:
	 * 1. Connecting to MQTT Broker
	 * 2. Waiting until 4 Clients are logged in
	 * 3. Handing out cards
	 * 4. Simulating 10 Rounds
	 * 5. Evaluation
	 * 6. Continue with 2.
	 */
	public static void main(String[] args) {
		logger.info("Running");
		GameManager instance = new GameManager();
		instance.connect();
		instance.game = new Game();
		Manager.start();
		while(true) {
			if(loggedInClients == 4 && clientsInLobby == 4) {
				instance.game.reset();

				logger.info("All 4 Clients in Lobby. Starting Game...");
				for(int i=0;i<instance.clients.length;i++)
				{	
//					instance.clients[i].getPlayer().setDate(LocalDateTime.now());
					Manager.persist(instance.clients[i].getPlayer());
				}
				instance.publishMessage(new ToClient_StateMsg(null, instance.game.nextState()));
				instance.handOutCards();
				while(!instance.game.nextState().equals(State.EVALUATION)) {
					instance.publishMessage(new ToClient_StateMsg(null, instance.game.getCurrentState()));
					logger.info(instance.game.getCurrentState().getStateName());
					instance.round();
				}

				instance.publishMessage(new ToClient_StateMsg(null, instance.game.nextState()));
				logger.info("Victory points re-party: " + instance.calcVictoryPoints());
				logger.info("Players in re-party: ");
				for(int i = 0; i<4 ; i++) {
					if(instance.clients[i].isRe())
					{
						instance.clients[i].getPlayer().setVictoryPoints(instance.calcVictoryPoints());
						logger.info(instance.clients[i].getPlayer().getName() + "   ");
					}
					else
						instance.clients[i].getPlayer().setVictoryPoints(-instance.calcVictoryPoints());
				}

				//generates Player[] from Client[] to create a ToClient_LeaderBoardMsg
				instance.publishMessage(new ToClient_LeaderBoardMsg(null, Arrays.stream(instance.clients).map(c -> c.getPlayer()).toArray(Player[]::new)));

				for(int i=0;i<instance.clients.length;i++)
				{	
//					instance.clients[i].getPlayer().setDate(LocalDateTime.now());
					Manager.persist(instance.clients[i].getPlayer());
				}
				logger.error(" \nGame Over.");
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				instance.publishMessage(new ToClient_StateMsg(null, instance.game.nextState()));
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}



	public int calcVictoryPoints() {
		int victoryPointsRe = 0;
		int pointsRe = 0;
		for(int i = 0; i< 10; i++) {
			Trick trick = game.getRounds()[i];
			boolean winnerIsRe = trick.evaluate().isRe();
			if(winnerIsRe) pointsRe += trick.getPoints();
			if(trick.getPoints()>=40) {
				if(winnerIsRe) victoryPointsRe++;
				else victoryPointsRe--;
			}
			victoryPointsRe+=trick.caughtFoxes();
		}
		if(pointsRe>120) { 
			victoryPointsRe++;
			if(pointsRe>150) {
				victoryPointsRe++;
				if(pointsRe>180) {
					victoryPointsRe++;
					if(pointsRe>210) {
						victoryPointsRe++;
						if(pointsRe==240) {
							victoryPointsRe++;
						}}}}
		} else {
			victoryPointsRe--;
			if(pointsRe<90) { 
				victoryPointsRe--;
				if(pointsRe<60) {
					victoryPointsRe--;
					if(pointsRe<30) {
						victoryPointsRe--;
						if(pointsRe==0) {
							victoryPointsRe--;
						}}}}
		}


		//TODO ANSAGEN PRÜFEN
		return victoryPointsRe;
	}

	/**
	 * Sends each Client 10 random Cards from Deck by publishing ToClient_AddCardMsgs
	 */
	public void handOutCards() {
		ArrayList<Card> deck = createDeck();
		Random rand = new Random();
		Card clubsQueen=new Card(CardColour.CLUB,CardValue.QUEEN);
		for(int client = 0; client < 4; client ++) {
			for(int i = 10; i > 0; i--) {
				int idx = rand.nextInt(deck.size());
				Card card = deck.get(idx);
				deck.remove(idx);
				card.setOwner(clients[client]);
				if(card.equals(clubsQueen))
					clients[client].setRe(true);
				publishMessage(new ToClient_AddCardMsg(clients[client].getId(), card));
			}
		}

	}

	/**
	 * Adds card to game and notifies clients by publishing a ToClient_PlayedCardMsg
	 */
	public void addPlayedCard(Card card) {
		game.clientPlaysCard(card);
		game.nextClient();
		publishMessage(new ToClient_PlayedCardMsg(null, game.getCurrentRound()));
		waitingForPlayedCard = false;
	}

	/**
	 * Adds two of each possible combination of colour and value to deck
	 */
	public ArrayList<Card> createDeck(){
		ArrayList<Card> deck = new ArrayList<>();
		for (CardColour colour : CardColour.values()) {
			for (CardValue value : CardValue.values()) {
				deck.add(new Card(colour, value));
				deck.add(new Card(colour, value));
			}
		}
		return deck;
	}

	/**
	 * Simulates a round
	 * 1. Notifies next Player using ToClient_NextPlyaerMsg
	 * 2. Waits for card
	 * ( 3. Card gets added from Callback class )
	 * 4. Repeat 1-3 for each client
	 * 5. Evaluates round and sets new starting client accordingly
	 */
	public void round() {
		game.setActiveClient(game.getStartingClient());
		for(int i = 0; i < 4; i++) {
			waitingForPlayedCard = true;
			publishMessage(new ToClient_NextPlayerMsg(null, clients[game.getActiveClient()].getPlayer().getName()));
			while(waitingForPlayedCard) {
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			logger.info(game.currentRoundToString().toString());

		}
		Client winner = game.getCurrentRound().evaluate();
		logger.info(winner.getPlayer().getName() + " won this round (" + game.getCurrentRound().getPoints() + " pts)");
		for (int i = 0; i < clients.length; i++) {
			if(clients[i].equals(winner)) game.setStartingClient(i);
		}
	}

	public Game getGame() 
	{
		return game;
	}


	/**
	 * Tries to log in a client, send information if login was successful via ToClient_LoginReaction
	 */
	public void login(Client client, String username, String password) {
		Player player = null;
		try {
			player = Manager.askPlayer(username, password);
		} catch (NoResultException e) {
			logger.info("Kein Spieler mit dem Namen " + username + " gefunden, versuche Spieler zu registrieren");
		}
			if(player != null) {
				for(Client currentClient : clients) {
					if(currentClient != null && currentClient.getPlayer() != null && 
							currentClient.getPlayer().getName().equals(username)) {
						logger.error("Der Spieler mit dem Namen " + username + " ist bereits eingeloggt!");
						return;
					}
				}
			} else if (!Manager.isPlayerExisting(username)){
				player = new Player(username, password);
				Manager.persist(player);
			}
			else {
				logger.error("Der Nutzername ist bereits vergeben");
				return;
			}

			if(loggedInClients>=4) return;
			client.setPlayer(player);
			for (int i = 0; i < clients.length; i++) {
				if(clients[i]==null) {
					clients[i] = client;
					break;
				}
			}
			loggedInClients++;
			publishMessage(new ToClient_LoginReactionMsg(client.getId(), player, true));
	}



	/**
	 * [untested]
	 * Removes a client
	 */
	public void logout(Client client) {
		int i = 0;
		for (; i < clients.length; i++) {
			if(clients[i].equals(client)) {
				clients[i] = null;
				break;
			}
		}

		//TODO prüfen
		for (int j = i; j < clients.length-1; j++) {
			clients[i] = clients[i+1];
		}

	}

	public void enterLobby(Client client) {
		for(Client currentClient : clients) {
			if(currentClient.equals(client)) {
				clientsInLobby++;
				break;
			}
		}
	}

	public void leaveLobby(Client client) {
		for(Client currentClient : clients) {
			if(currentClient.equals(client)) {
				clientsInLobby--;
				break;
			}
		}
	}

	/**
	 * Connects to MQTT Broker and subscribes to topics
	 */
	public  void connect() {
		try {
			mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId());
			mqttClient.connect();
			mqttClient.setTimeToWait(10000000);
			mqttClient.setCallback(new GameManagerMqttCallback(this));
			mqttClient.subscribe(ToServer_LoginMsg.type);
			mqttClient.subscribe(ToServer_LogoutMsg.type);
			mqttClient.subscribe(ToServer_PlayedCardMsg.type);
			mqttClient.subscribe(ToServer_LeaderBoardMsg.type);
			mqttClient.subscribe(ToServer_EnterLobbyMsg.type);
			mqttClient.subscribe(ToServer_LeaveLobbyMsg.type);
		} catch (MqttException e) {
			logger.error("Could not connect to MQTT Broker (" + e.getMessage() + ")");
		}
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



	public void sendLeaderboard(Client sender) {
		publishMessage(new ToClient_OverallScoreMsg(sender.getId(), Manager.askLeaderboard()));
	}

}
