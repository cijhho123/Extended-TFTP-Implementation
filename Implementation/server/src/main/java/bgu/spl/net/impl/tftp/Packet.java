package bgu.spl.net.impl.tftp;

public class  Packet {
    static final int PACKET_MAX_SIZE = 512;
    byte[] bytes ;  

    int currentIndex ;

    public Packet(){
        this(PACKET_MAX_SIZE);
    }

    public Packet(int size){
        bytes = new byte[size];
        currentIndex = 0;
    }


    public int getAmountOfBytesLeft(){
        return bytes.length - currentIndex;
    }

    public void continueRead(byte[] msg){

    }

    public int continueWrite(byte[] msg){
        for(int shift = 0; shift < msg.length; shift++)
            bytes[shift + currentIndex] = msg[shift];
        
        currentIndex += msg.length;

        return getAmountOfBytesLeft();
    }

    public byte[] sendPacket(){
        return bytes;
    }
}
