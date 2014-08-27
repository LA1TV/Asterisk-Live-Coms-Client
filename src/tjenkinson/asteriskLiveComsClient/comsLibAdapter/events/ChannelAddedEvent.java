package tjenkinson.asteriskLiveComsClient.comsLibAdapter.events;

 
public class ChannelAddedEvent extends AbstractChannelEvent {
	
	private String name = null;
	public ChannelAddedEvent(int id, String name) {
		super(id);
		this.name = name;
	}
	
	public String getName() {
		return name;
	}
}
