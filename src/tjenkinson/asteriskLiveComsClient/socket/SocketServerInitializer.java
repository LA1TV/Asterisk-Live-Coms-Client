package tjenkinson.asteriskLiveComsClient.socket;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import tjenkinson.asteriskLiveComsClient.comsLibAdapter.ComsLibAdapter;

public class SocketServerInitializer extends ChannelInitializer<SocketChannel> {

	private ComsLibAdapter comsLibAdapter;
	
	public SocketServerInitializer(ComsLibAdapter comsLibAdapter) {
		this.comsLibAdapter = comsLibAdapter;
	}

	@Override
	public void initChannel(SocketChannel ch) throws Exception {
		ch.pipeline().addLast(
				new HttpRequestDecoder(),
				new HttpObjectAggregator(65536),
				new HttpResponseEncoder(),
				new WebSocketServerProtocolHandler("/ws"),
				new ServerSocketHandler(comsLibAdapter));
	}
}