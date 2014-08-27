package tjenkinson.asteriskLiveComsClient.comsLibAdapter.events;

public abstract class AbstractChannelEvent extends ComsLibAdapterEvent {
	
	private int id;
	
	public AbstractChannelEvent(int id) {
		this.id = id;
	}
	
	public int getChannelId() {
		return id;
	}
}
