package tjenkinson.asteriskLiveComsClient;

import tjenkinson.asteriskLiveComsClient.comsLibAdapter.ComsLibAdapter;
import tjenkinson.asteriskLiveComsClient.socket.SocketServer;

public class Client {
	
	public Client(String serverHost, int serverPort, String host, int port, String config) {
		
		try {
			new SocketServer(host, port, new ComsLibAdapter(serverHost, serverPort, config)).run();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
