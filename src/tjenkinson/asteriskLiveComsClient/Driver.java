package tjenkinson.asteriskLiveComsClient;


public class Driver {
	// args: coms server ip, coms server port, this ip, this port, this config location
	public static void main(String[] args) throws Exception {
		if (args.length < 5) {
			System.out.println("Missing arguments.");
			System.exit(1);
		}
		String serverHost = args[0];
		int serverPort = Integer.parseInt(args[1], 10);
		String host = args[2];
		int port = Integer.parseInt(args[3], 10);
		String config = args[4];
		
		new Client(serverHost, serverPort, host, port, config);
	}
}