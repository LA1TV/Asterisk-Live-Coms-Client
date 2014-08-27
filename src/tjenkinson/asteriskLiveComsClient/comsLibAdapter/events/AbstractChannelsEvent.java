package tjenkinson.asteriskLiveComsClient.comsLibAdapter.events;

import java.util.ArrayList;

public abstract class AbstractChannelsEvent extends ComsLibAdapterEvent {
	private ArrayList<Integer> ids;
	
	public AbstractChannelsEvent(ArrayList<Integer> ids) {
		this.ids = ids;
	}
	
	public ArrayList<Integer> getChannelIds() {
		return ids;
	}
}
