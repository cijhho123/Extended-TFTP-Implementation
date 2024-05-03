package bgu.spl.net.srv;


import java.io.IOException;
@SuppressWarnings("unused")

public interface Connections<T> {

    //return the connectionID
    int connect(int connectionId, ConnectionHandler<T> handler);

    boolean send(int connectionId, T msg);

    boolean disconnect(int connectionId);

    int getNextID();
}
