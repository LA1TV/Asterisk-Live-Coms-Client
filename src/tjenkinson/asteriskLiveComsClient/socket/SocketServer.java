package tjenkinson.asteriskLiveComsClient.socket;

import java.net.InetSocketAddress;

import tjenkinson.asteriskLiveComsClient.comsLibAdapter.ComsLibAdapter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class SocketServer {
	
	private String host;
	private final int port;
	private ComsLibAdapter comsLibAdapter;

	public SocketServer(String host, int port, ComsLibAdapter comsLibAdapter) {
		this.host = host;
		this.port = port;
		this.comsLibAdapter = comsLibAdapter;
	}

	public void run() throws Exception {
		
		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.childHandler(new SocketServerInitializer(comsLibAdapter));
 
			b.bind(new InetSocketAddress(host, port)).sync().channel().closeFuture().sync();
		}
		finally {
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}
}