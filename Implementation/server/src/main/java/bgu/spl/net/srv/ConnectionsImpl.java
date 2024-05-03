package bgu.spl.net.srv;

import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl <T> implements Connections <T> {

    private final ConcurrentHashMap<Integer,ConnectionHandler<T>> connectionsMap ;
    private int nextID ;

    public ConnectionsImpl(){
        connectionsMap = new ConcurrentHashMap<Integer, ConnectionHandler<T>>();
        nextID = 0;
    }

    
    public int connect(int connectionId, ConnectionHandler<T> handler){
        if(handler == null)
            return -1;

        //  https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html#computeIfAbsent-K-java.util.function.Function-
        if(!connectionsMap.contains(connectionId)){
            connectionsMap.put(connectionId,handler);
            return connectionId;
        }

        else
            return -1;
    }

    public boolean send(int connectionId, T msg){
        ConnectionHandler<T> currentHandler = connectionsMap.get(connectionId);

        if(currentHandler != null){
            currentHandler.send(msg);
            return true;
        }

        return false;
    }

    //return true iff connectionId is associated with a connection handler that was removing upon calling the function
    public boolean disconnect(int connectionId){
        //  https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html#remove-java.lang.Object-
        return null != connectionsMap.remove(connectionId);
    }

    public int getNextID(){
        nextID++;
        return nextID-1;        
    }
}