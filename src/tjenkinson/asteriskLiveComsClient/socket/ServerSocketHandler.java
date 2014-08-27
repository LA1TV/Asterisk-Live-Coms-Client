package tjenkinson.asteriskLiveComsClient.socket;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.ArrayList;
import java.util.Hashtable;

import tjenkinson.asteriskLiveComsClient.comsLibAdapter.ComsLibAdapter;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelAddedEvent;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelRemovedEvent;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelToHoldingEvent;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ChannelsToRoomEvent;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ComsLibAdapterEvent;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.events.ComsLibAdapterEventListener;
import douglascrockford.json.JSONArray;
import douglascrockford.json.JSONException;
import douglascrockford.json.JSONObject;

public class ServerSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

	private ComsLibAdapter comsLibAdapter;
	private ChannelHandlerContext channelCtx = null;
	// they way the client authenticates isn't great but I don't have time to figure out ssl
	private boolean authenticated = false;
	private String authenticationPassword = "j3ev8uphlaclaMl6";
	
	public ServerSocketHandler(ComsLibAdapter comsLibAdapter) {
		this.comsLibAdapter = comsLibAdapter;
		comsLibAdapter.addEventListener(new ComsLibAdapterEventListener() {
			@Override
			public void onEvent(ComsLibAdapterEvent e) {
				if (channelCtx == null) {
					// don't do anything if the channel hasn't been initialized yet
					return;
				}
				if (!hasAuthenticated()) {
					// don't send anything if they haven't authenticated yet
					return;
				}
				if (e.getClass().getSimpleName().equals("ChannelAddedEvent")) {
					ChannelAddedEvent event = (ChannelAddedEvent) e;
					ArrayList<ReturnObj> returnObjects = new ArrayList<ReturnObj>();
					Hashtable<String,Object> payload = new Hashtable<String,Object>();
					payload.put("action", "connection");
					payload.put("id", event.getChannelId());
					payload.put("name", event.getName());
					returnObjects.add(new ReturnObj(0, null, payload));
					JSONObject[] tmpArray = generateReturnObjArray(returnObjects);
					channelCtx.writeAndFlush(new TextWebSocketFrame(new JSONArray(tmpArray).toString()));
				}
				else if (e.getClass().getSimpleName().equals("ChannelRemovedEvent")) {
					ChannelRemovedEvent event = (ChannelRemovedEvent) e;
					ArrayList<ReturnObj> returnObjects = new ArrayList<ReturnObj>();
					Hashtable<String,Object> payload = new Hashtable<String,Object>();
					payload.put("action", "disconnection");
					payload.put("id", event.getChannelId());
					returnObjects.add(new ReturnObj(0, null, payload));
					JSONObject[] tmpArray = generateReturnObjArray(returnObjects);
					channelCtx.writeAndFlush(new TextWebSocketFrame(new JSONArray(tmpArray).toString()));
				}
				else if (e.getClass().getSimpleName().equals("ChannelsToRoomEvent")) {
					ChannelsToRoomEvent event = (ChannelsToRoomEvent) e;
					ArrayList<ReturnObj> returnObjects = new ArrayList<ReturnObj>();
					Hashtable<String,Object> payload = new Hashtable<String,Object>();
					payload.put("action", "linking");
					payload.put("ids", event.getChannelIds());
					returnObjects.add(new ReturnObj(0, null, payload));
					JSONObject[] tmpArray = generateReturnObjArray(returnObjects);
					channelCtx.writeAndFlush(new TextWebSocketFrame(new JSONArray(tmpArray).toString()));
				}
				else if (e.getClass().getSimpleName().equals("ChannelToHoldingEvent")) {
					ChannelToHoldingEvent event = (ChannelToHoldingEvent) e;
					ArrayList<ReturnObj> returnObjects = new ArrayList<ReturnObj>();
					Hashtable<String,Object> payload = new Hashtable<String,Object>();
					// send linking event with just the one channel
					payload.put("action", "linking");
					payload.put("ids", new int[]{event.getChannelId()});
					returnObjects.add(new ReturnObj(0, null, payload));
					JSONObject[] tmpArray = generateReturnObjArray(returnObjects);
					channelCtx.writeAndFlush(new TextWebSocketFrame(new JSONArray(tmpArray).toString()));
				}
			}
		});
	}

	@Override
	public void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
		String request = frame.text();
		JSONObject inputLineJSON = null;
		Boolean parseError = false;
		Boolean close = false;
		ArrayList<ReturnObj> returnObjects = new ArrayList<ReturnObj>();
		
		try {
			inputLineJSON = new JSONObject(request);
		}
		catch(JSONException e) {
			returnObjects.add(new ReturnObj(1, "Could not parse JSON string.", null));
			parseError = true;
		}
		
		if (!hasAuthenticated()) {
			// try and authenticate
			if (!inputLineJSON.has("action") || inputLineJSON.getString("action") == null) {
				// invalid. do nothing
			}
			else if (!inputLineJSON.has("payload") || inputLineJSON.getString("payload") == null) {
				// invalid. do nothing
			}
			else if (authenticate(inputLineJSON.getString("payload"))) {
				// authentication was successful
				Hashtable<String,Object> payload = new Hashtable<String,Object>();
				payload.put("action", "authenticated");
				returnObjects.add(new ReturnObj(0, "Authenticated!", payload));
			}
			
		}
		
		// only read other commands if authenticated and no parse error
		if (!parseError && hasAuthenticated()) {
			if (!inputLineJSON.has("action") || inputLineJSON.getString("action") == null) {
				returnObjects.add(new ReturnObj(1, "The action was not specified.", null));
			}
			else if (inputLineJSON.getString("action").equals("getInitialData")) {
				
				// first send all the channel added events
				ArrayList<Hashtable<String,Object>> channels = comsLibAdapter.getRemappedChannels();
				for(int i=0; i<channels.size(); i++) {
					Hashtable<String,Object> payload = new Hashtable<String,Object>();
					payload.put("action", "connection");
					payload.put("id", channels.get(i).get("id"));
					payload.put("name", channels.get(i).get("name"));
					returnObjects.add(new ReturnObj(0, null, payload));
				}
				// then send all the linking events
				int[][] groups = comsLibAdapter.getGroups();
				for(int i=0; i<groups.length; i++) {
					Hashtable<String,Object> payload = new Hashtable<String,Object>();
					payload.put("action", "linking");
					payload.put("ids", groups[i]);
					returnObjects.add(new ReturnObj(0, null, payload));
				}
			}
			else if (inputLineJSON.getString("action").equals("link")) {
				try {
					JSONArray chanIds = inputLineJSON.getJSONArray("payload");
					int[] ids = new int[chanIds.length()];
					for (int i=0; i<chanIds.length(); i++) {
						ids[i] = chanIds.getInt(i);
					}
					comsLibAdapter.routeChannels(ids);
				}
				catch (JSONException e) {
					returnObjects.add(new ReturnObj(101, "JSON invalid for command.", null));
				}
			}
			else if (inputLineJSON.getString("action").equals("getGroups")) {
				returnObjects.add(new ReturnObj(0, null, comsLibAdapter.getGroups()));
			}
			else if (inputLineJSON.getString("action").equals("disconnect")) {
				try {
					JSONArray chanIds = inputLineJSON.getJSONArray("payload");
					int[] ids = new int[chanIds.length()];
					for (int i=0; i<chanIds.length(); i++) {
						ids[i] = chanIds.getInt(i);
					}
					comsLibAdapter.sendToHolding(ids);
				}
				catch (JSONException e) {
					returnObjects.add(new ReturnObj(101, "JSON invalid for command.", null));
				}
			}
			else if (inputLineJSON.getString("action").equals("ping")) {
				Hashtable<String,Object> payload = new Hashtable<String,Object>();
				payload.put("action", "pingResponse");
				returnObjects.add(new ReturnObj(0, "Hello!", payload));
			}
			else if (inputLineJSON.getString("action").equals("bye")) {
				returnObjects.add(new ReturnObj(0, "Closing connection.", null));
				close = true;
			}
			else if (returnObjects.isEmpty()) {
				returnObjects.add(new ReturnObj(3, "Nothing to process command.", null));
			}
		}
		
		// if there is no data to send don't send any
		// also don't send anything if not authenticated
		if (!returnObjects.isEmpty() && hasAuthenticated()) {
		
			JSONObject[] tmpArray = generateReturnObjArray(returnObjects);
			ChannelFuture future = ctx.write(new TextWebSocketFrame(new JSONArray(tmpArray).toString()));
			if (close) {
				future.addListener(ChannelFutureListener.CLOSE);
			}
		}
	}
	
	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
	}
	
	public void channelActive(ChannelHandlerContext ctx) {
		this.channelCtx = ctx;
	}
	
	private boolean hasAuthenticated() {
		return authenticated;
	}
	
	private boolean authenticate(String pass) {
		if (pass.equals(authenticationPassword)) {
			authenticated = true;
		}
		return hasAuthenticated();
	}
	
	private JSONObject[] generateReturnObjArray(ArrayList<ReturnObj> returnObjects) {
		JSONObject[] tmpArray = new JSONObject[returnObjects.size()];
		for (int i=0; i<returnObjects.size(); i++) {
			tmpArray[i] = new JSONObject(returnObjects.get(i).getData());
		}
		return tmpArray;
	}
	
}
