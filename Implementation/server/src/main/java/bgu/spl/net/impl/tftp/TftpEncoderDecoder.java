package bgu.spl.net.impl.tftp;

import java.nio.ByteBuffer;
import java.util.Arrays;

import bgu.spl.net.api.MessageEncoderDecoder;

public class TftpEncoderDecoder implements MessageEncoderDecoder<byte[]> {

    private final ByteBuffer OPCodeBuffer = ByteBuffer.allocate(2); //the OPCode is at fixed length of 2 bytes (short) in big endian
    private byte[] DataByteArray = null;    // first two bytes contain the OPCode
    protected short OPCode = -1;
    protected int objectBytesIndex = -1;


    @Override
    public byte[] decodeNextByte(byte nextByte) {

        if (DataByteArray == null) {      //indicates that we are still reading the OPCode
            OPCodeBuffer.put(nextByte);
            if (!OPCodeBuffer.hasRemaining()) {     
                OPCodeBuffer.flip();

                DataByteArray = new byte[512 + 2 + 2 + 2];   // max length for: 2 - OPcode,  2 - packet size, 2 - block number, 512 - data    
                DataByteArray[0] = OPCodeBuffer.array()[0]; 
                DataByteArray[1] = OPCodeBuffer.array()[1];

                OPCode = byteArrayToShort(OPCodeBuffer.array());
                objectBytesIndex = 2;

                OPCodeBuffer.clear();

                if (OPCode == TftpProtocol.DISC || OPCode == TftpProtocol.DIRQ){
                    short resultedOPCode = OPCode; 
                    DataByteArray = null;
                    OPCode = -1;
                    objectBytesIndex = -1;

                    return shortToByteArray(resultedOPCode);
                }
                    
            }
        } else {       //If we already have the OPCode

            if ( shouldStop(DataByteArray, nextByte)) {
                DataByteArray[objectBytesIndex] = nextByte;
                objectBytesIndex++;

                byte[] result = Arrays.copyOfRange(DataByteArray, 0, objectBytesIndex);  
                DataByteArray = null;
                OPCode = -1;
                objectBytesIndex = -1;

                return result;
            }

            DataByteArray[objectBytesIndex] = nextByte;
            objectBytesIndex++;
        }

        return null;
    }

    @Override
    public byte[] encode(byte[] message) {
        return message;
    }

    
    //check if we've reached the end of the input for the decoded message 
    private boolean shouldStop (byte[] b, byte nextByte){
        //DIRQ or DISC
        if (OPCode == TftpProtocol.DISC || OPCode == TftpProtocol.DIRQ)
            return true;

        //ACK
        if(OPCode == TftpProtocol.ACK)
            return objectBytesIndex == 3;  
        
        //every other operation besides DATA package
        if(OPCode != TftpProtocol.DATA )
            return (nextByte == 0x00);

        //if the current package is DATA
        if(objectBytesIndex < 6)    //if we haven't finished reading the Packet Size and Block Number
            return false;
        
        //check if we've reached the end of the packet's length
        short packetSize = byteArrayToShort( new byte[]{b[2], b[3]} );
        return objectBytesIndex == (packetSize + 6) - 1;   //2 OPCode + 2 packetsize + 2 blocknumber, (-1) since we count from 0

    }

    // converting short to byte array
    public static byte[] shortToByteArray(short a){
        byte [] a_bytes = new byte []{( byte ) ( a >> 8) , ( byte ) ( a & 0xff ) };
        return a_bytes;
    }

    // converting byte array to a short
    public static short byteArrayToShort(byte [] b){
        short b_short = (short) (((short) b[0]) << 8 | (short) (b[1]) & 0x00ff);
        return b_short;
    }
}