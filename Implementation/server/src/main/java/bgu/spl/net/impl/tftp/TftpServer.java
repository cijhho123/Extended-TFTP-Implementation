package bgu.spl.net.impl.tftp;

import bgu.spl.net.srv.Server;

public class TftpServer {
    public static void main(String[] args) {
        int port;

        if(args.length != 0)
            port = Integer.parseInt(args[0]);
        else 
            port = 7777;
        
        
        //int port    = 7777;
        //BaseServer<byte[]> server = BaseServer.threadPerClient(port,()->new TftpProtocol(),()->new TftpEncoderDecoder());
        //server.serve();

        Server.threadPerClient(
            port, 
            () -> new TftpProtocol(),
            () -> new TftpEncoderDecoder()).serve();
    
    }
}

