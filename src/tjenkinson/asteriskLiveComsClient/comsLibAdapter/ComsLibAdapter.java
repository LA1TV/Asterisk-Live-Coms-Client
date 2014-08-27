package tjenkinson.asteriskLiveComsClient.comsLibAdapter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ComsLibAdapterEvent;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ComsLibAdapterEventListener;
import tjenkinson.asteriskLiveComsLib.program.LiveComsServer;
import tjenkinson.asteriskLiveComsLib.program.events.AsteriskLiveComsEvent;
import tjenkinson.asteriskLiveComsLib.program.events.AsteriskLiveComsEventListener;
import tjenkinson.asteriskLiveComsLib.program.events.ChannelAddedEvent;
import tjenkinson.asteriskLiveComsLib.program.events.ChannelRemovedEvent;
import tjenkinson.asteriskLiveComsLib.program.events.ChannelToHoldingEvent;
import tjenkinson.asteriskLiveComsLib.program.events.ChannelsToRoomEvent;
import tjenkinson.asteriskLiveComsLib.program.exceptions.AccessAlreadyGrantedException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.AlreadyConnectedException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.ChannelIDInvalidException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.ChannelNotVerifiedException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.NotConnectedException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.RequestJSONInvalidException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.RequiresMoreChannelsException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.UnableToConnectException;
import tjenkinson.asteriskLiveComsLib.program.exceptions.UnknownException;
import douglascrockford.json.JSONException;

public class ComsLibAdapter {
	
	private String configFileLocation;
	private LiveComsServer comsServer;
	// contains the map of phones that are allowed. the value will be null if the phone has not yet called in.
	private HashMap<String,HashMap<String,Object>> phoneMap = new HashMap<String,HashMap<String,Object>>();
	// contains the event listeners
	private ArrayList<ComsLibAdapterEventListener> listeners = new ArrayList<ComsLibAdapterEventListener>();
	
	public ComsLibAdapter(String serverHost, int serverPort, String configFileLocation) {
		this.configFileLocation = configFileLocation;
		populatePhoneMap();
		comsServer = new LiveComsServer(serverHost, serverPort);
		tryAndConnect(true);
		updatePhoneMap(false);
		comsServer.addEventListener(new AsteriskLiveComsEventListener() {

			@Override
			public synchronized void onAsteriskLiveComsEvent(AsteriskLiveComsEvent e) {
				if (e.getClass().getSimpleName().equals("ChannelAddedEvent")) {
					ChannelAddedEvent event = (ChannelAddedEvent) e;
					
					// grant access to phones that are allowed and put them in the array
					String user = getActualUserFromResponse((String) event.getInfo().get("name"));
					if (phoneMap.containsKey(user)) {
						// this phone is allowed
						// grant access
						try {
							comsServer.grantAccess((int) event.getInfo().get("id"), false); // 2nd param enables hold music
						} catch (JSONException | NotConnectedException
								| UnknownException | RequestJSONInvalidException
								| ChannelIDInvalidException
								| AccessAlreadyGrantedException e1) {
							e1.printStackTrace();
						}
						// update in phone map
						updatePhoneMapInfo(user, event.getInfo());
						// broadcast the event
						HashMap<String,Object> phoneMapData = phoneMap.get(user);
						broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelAddedEvent((int) phoneMapData.get("id"), (String) phoneMapData.get("name")));
					}
					else {
						// this phone is not allowed
						try {
							comsServer.denyAccess((int) event.getInfo().get("id"));
						} catch (JSONException | NotConnectedException
								| RequestJSONInvalidException
								| ChannelIDInvalidException | UnknownException e1) {
							e1.printStackTrace();
						}
						
					}
				}
				else if (e.getClass().getSimpleName().equals("ChannelRemovedEvent")) {
					ChannelRemovedEvent event = (ChannelRemovedEvent) e;
					int phoneMapId = getPhoneMapId(event.getChannelId());
					String user = getUserFromPhoneMapId(phoneMapId);
					if (user != null) {
						// update in phone map
						updatePhoneMapInfo(user, null);
						// broadcast event
						broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelRemovedEvent(phoneMapId));
					}
				}
				else if (e.getClass().getSimpleName().equals("ChannelsToRoomEvent")) {
					ChannelsToRoomEvent event = (ChannelsToRoomEvent) e;
					ArrayList<Integer> phoneIdsL = event.getChannelIds();
					int[] phoneIds = new int[phoneIdsL.size()];
					for(int i=0; i<phoneIdsL.size(); i++) {
						phoneIds[i] = (int) phoneIdsL.get(i);
					}
					int[] phoneMapIds = getPhoneMapIds(phoneIds);
					ArrayList<Integer> phoneMapIdsL = new ArrayList<Integer>();
					for(int i=0; i<phoneMapIds.length; i++) {
						phoneMapIdsL.add(phoneMapIds[i]);
					}
					broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelsToRoomEvent(phoneMapIdsL));
				}
				else if (e.getClass().getSimpleName().equals("ChannelToHoldingEvent")) {
					ChannelToHoldingEvent event = (ChannelToHoldingEvent) e;
					broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelToHoldingEvent(getPhoneMapId(event.getChannelId())));
				}
				// wait and then try again if connection goes
				else if (e.getClass().getSimpleName().equals("ConnectionLostEvent")) {
					System.out.println("Connection lost.");
					disconnectAllPhonesFromMap(); // make all the phones disconnected. they will be brought back up when it reconnects
					tryAndConnect(); // this will block until connected again
					// resync phone map and broadcast events for anything that has changed
					updatePhoneMap(true);
				}
				
			}
			
		});
	}
	
	private void tryAndConnect() {
		tryAndConnect(false);
	}
	
	// this is blocking
	private synchronized void tryAndConnect(boolean initialConnection) {
		
		if (comsServer.isConnected()) {
			return;
		}
		
		boolean ignoreDelay = initialConnection;
		while(!comsServer.isConnected()) {
			
			if (!ignoreDelay) {
				System.out.println("Trying to connect in 10 seconds.");
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
			else {
				ignoreDelay = false;
			}
			
			System.out.println("Connecting...");
			try {
				comsServer.connect();
			}
			catch (AlreadyConnectedException e) {
				e.printStackTrace();
			}
			catch (UnableToConnectException e) {
				System.out.println("Failed to connect.");
				// it will try again
			}
		}
		System.out.println("Connected!");
	}
	
	private synchronized void disconnectAllPhonesFromMap() {
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if (phone.get("channelData") != null) {
				// this phone is connected. remove it
				// update in phone map
				String user = pairs.getKey();
				int phoneMapId = (int) phone.get("id");
				updatePhoneMapInfo(user, null);
				// broadcast event
				broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelRemovedEvent(phoneMapId));
			}
		}
	}
	
	private synchronized void updatePhoneMap(boolean broadcastEvents) {
		// add channels that are already connected
		try {
			
			Set<String> unseenUsers =  new HashSet<String>(phoneMap.keySet()); // CLONE instead of reference
			ArrayList<Hashtable<String, Object>> channels = comsServer.getChannels();
			// loop though channels updating them in the map or denying access/disconnecting them if they are not allowed
			for (int i=0; i<channels.size(); i++) {
				Hashtable<String, Object> channel = channels.get(i);
				String user = getActualUserFromResponse((String) channel.get("name"));
				if (phoneMap.containsKey(user)) {
					unseenUsers.remove(user);
					// this channel is allowed
					if(!((boolean) channel.get("verified"))) {
						// channel hasn't already been granted access so grant access now
						try {
							comsServer.grantAccess((int) channel.get("id"), false); // the 2nd param enables hold music 
						} catch (RequestJSONInvalidException
								| ChannelIDInvalidException
								| AccessAlreadyGrantedException e1) {
							e1.printStackTrace();
						}
					}
					// update the phone in the phone map
					updatePhoneMapInfo(user, channel);
					if (broadcastEvents) {
						// broadcast the event
						HashMap<String,Object> phoneMapData = phoneMap.get(user);
						broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelAddedEvent((int) phoneMapData.get("id"), (String) phoneMapData.get("name")));
					}
				}
				else {
					// deny access
					try {
						comsServer.denyAccess((int) channel.get("id"));
					} catch (RequestJSONInvalidException
							| ChannelIDInvalidException e1) {
						e1.printStackTrace();
					}
				}
			}
			
			// now determine if there are any channels that have disconnected
			for (Iterator<String> it=unseenUsers.iterator(); it.hasNext();) {
				// this channel has been disconnected
				String user =it.next();
				// update in phone map
				updatePhoneMapInfo(user, null);
				// broadcast event
				if (broadcastEvents) {
					broadcastEvent(new tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelRemovedEvent((int) phoneMap.get(user).get("id")));
				}
			}
			
		} catch (JSONException | NotConnectedException | UnknownException e2) {
			e2.printStackTrace();
		}
	}
	
	// populate the phone map from the data in the config file
	// config file cols are user, id, name
	private void populatePhoneMap() {
		BufferedReader br = null;
		String line = null;
		
		try {
			br = new BufferedReader(new FileReader(this.configFileLocation));
			while((line = br.readLine()) != null && !line.equals("")) {
				String[] columns = line.split(",");
				System.out.println("Adding: '"+columns[0]+"' "+Integer.parseInt(columns[1], 10)+" '"+columns[2]+"'");
				addPhoneToPhoneMap(columns[0], Integer.parseInt(columns[1], 10), columns[2]);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private synchronized void addPhoneToPhoneMap(String user, int id, String name) {
		HashMap<String,Object> a = new HashMap<String,Object>();
		a.put("id", id);
		a.put("name", name);
		a.put("channelData", null);
		phoneMap.put(user, a);
	}
	
	private synchronized void updatePhoneMapInfo(String key, Hashtable<String,Object> info) {
		phoneMap.get(key).put("channelData", info);
	}
	
	private synchronized String getActualUserFromResponse(String response) {
		Pattern pattern = Pattern.compile("^SIP/(\\w+?)\\-.*$");
		Matcher matcher = pattern.matcher(response);
		String name = null;
		if (matcher.find()) {
			name = matcher.group(1);
		}
		return name;
	}
	
	public synchronized void addEventListener(ComsLibAdapterEventListener listener) {
		listeners.add(listener);
	}
	
	public synchronized void removeEventListener(ComsLibAdapterEventListener listener) {
		listeners.remove(listener);
	}
	
	private synchronized void broadcastEvent(ComsLibAdapterEvent e) {
		for (int i=0; i<listeners.size(); i++) {
			listeners.get(i).onEvent(e);
		}
	}
	
	// gets the user matching the corresponding phone map id
	// returns null if there is no user with that id
	public synchronized String getUserFromPhoneMapId(int id) {
		// loop through all phones
		boolean found = false;
		String user = null;
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext() && !found) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if ((int) phone.get("id") == id) {
				found = true;
				user = pairs.getKey();
			}
		}
		return user;
	}
	
	// gets the channels with the ids remapped to the ones on the switchboard
	public synchronized ArrayList<Hashtable<String,Object>> getRemappedChannels() {
		ArrayList<Hashtable<String,Object>> channels = new ArrayList<Hashtable<String,Object>>();
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if (phone.get("channelData") != null) {
				// this phone is connected
				Hashtable<String, Object> remappedChannelData = new Hashtable<String, Object>();
				remappedChannelData.put("id", phone.get("id"));
				remappedChannelData.put("name", phone.get("name"));		
				channels.add(remappedChannelData);
			}
		}
		return channels;
	}
	
	// maps the phone map id to the actual id
	private synchronized int[] getActualIds(int[] sourceIds) {
		int[] remappedIds = new int[sourceIds.length];
		int index = 0;
		// loop through all phones and add ones with matching ids
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if (phone.get("channelData") != null) {
				// this phone is connected
				@SuppressWarnings("unchecked")
				Hashtable<String, Object> channelData = (Hashtable<String, Object>) phone.get("channelData");
				if (doesIntArrayContainInt(sourceIds,(int) phone.get("id"))) {
					remappedIds[index++] = (int) channelData.get("id");
				}
			}
		}
		return remappedIds;
	}
	
	// maps the actual id to the phone map id
	private synchronized int[] getPhoneMapIds(int[] sourceIds) {
		int[] remappedIds = new int[sourceIds.length];
		int index = 0;
		// loop through all phones and add ones with matching ids
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if (phone.get("channelData") != null) {
				// this phone is connected
				@SuppressWarnings("unchecked")
				Hashtable<String, Object> channelData = (Hashtable<String, Object>) phone.get("channelData");
				if (doesIntArrayContainInt(sourceIds,(int) channelData.get("id"))) {
					remappedIds[index++] = (int) phone.get("id");
				}
			}
		}
		return remappedIds;
	}
	
	// maps the phone map id to the actual id
	@SuppressWarnings("unused")
	private synchronized int getActualId(int sourceId) {
		int remappedId = 0;
		boolean found = false;
		// loop through all phones and add ones with matching ids
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext() && !found) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if (phone.get("channelData") != null) {
				// this phone is connected
				@SuppressWarnings("unchecked")
				Hashtable<String, Object> channelData = (Hashtable<String, Object>) phone.get("channelData");
				if (sourceId == (int) phone.get("id")) {
					remappedId = (int) channelData.get("id");
					found = true;
				}
			}
		}
		return remappedId;
	}
	
	// maps the actual id to the phone map id
	private synchronized int getPhoneMapId(int sourceId) {
		int remappedId = 0;
		boolean found = false;
		// loop through all phones and add ones with matching ids
		Iterator<Entry<String, HashMap<String, Object>>> it = phoneMap.entrySet().iterator();
		while (it.hasNext() && !found) {
			Entry<String, HashMap<String, Object>> pairs = it.next();
			HashMap<String,Object> phone = pairs.getValue();
			if (phone.get("channelData") != null) {
				// this phone is connected
				@SuppressWarnings("unchecked")
				Hashtable<String, Object> channelData = (Hashtable<String, Object>) phone.get("channelData");
				if (sourceId == (int) channelData.get("id")) {
					remappedId = (int) phone.get("id");
					found = true;
				}
			}
		}
		return remappedId;
	}
	
	public synchronized void routeChannels(int[] phoneMapIds) throws JSONException, NotConnectedException, RequestJSONInvalidException, ChannelIDInvalidException, ChannelNotVerifiedException, RequiresMoreChannelsException, UnknownException {
		if (phoneMapIds.length == 1) {
			// if it's only one phone this should actually be a send to holding command
			sendToHolding(phoneMapIds);
			return;
		}
		else if (phoneMapIds.length < 1) {
			return;
		}
		comsServer.routeChannels(getActualIds(phoneMapIds));
	}
	
	public int[][] getGroups() throws JSONException, NotConnectedException, UnknownException {
		int[][] groups = comsServer.getGroups();
		int[][] remappedGroups = new int[groups.length][];
		for (int i=0; i<groups.length; i++) {
			remappedGroups[i] = getPhoneMapIds(groups[i]);
		}
		return remappedGroups;
	}
	
	public synchronized void sendToHolding(int[] phoneMapIds) throws JSONException, NotConnectedException, RequestJSONInvalidException, ChannelIDInvalidException, ChannelNotVerifiedException, UnknownException {
		comsServer.sendToHolding(getActualIds(phoneMapIds));
	}
	
	private synchronized boolean doesIntArrayContainInt(int[] haystack, int needle) {
		for(int i=0; i<haystack.length; i++) {
			if (haystack[i] == needle) {
				return true;
			}
		}
		return false;
	}
	
}
