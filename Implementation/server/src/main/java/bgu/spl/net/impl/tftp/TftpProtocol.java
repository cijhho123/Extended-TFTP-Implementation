package bgu.spl.net.impl.tftp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.Connections;

public class TftpProtocol implements BidiMessagingProtocol<byte[]>  {

    //constant values
    protected static final short  RRQ = 1, WRQ = 2, DATA = 3, ACK = 4, ERROR = 5, DIRQ = 6, LOGRQ = 7, DELRQ = 8, BCAST = 9, DISC = 10;
    protected static final String basePath = "./Files/";

    //fields
    private static ConcurrentHashMap<Integer,String> usernameMap = new ConcurrentHashMap<>();;

    private Connections<byte[]> connections;
    private int ID ;
    private boolean isLoggedIn;
    private boolean shouldTerminate;

    //file related
    private static ConcurrentHashMap<Integer,String> filesGettingCreated = new ConcurrentHashMap<>();
    private boolean isCreatingFile;
    private FileOutputStream out;

    //error related
    static String [] defaultErrorMessages = {
        "Undefined error",
        "File not found - RRQ DELRQ of non-existing file.",
        "Access violation – File cannot be written, read or deleted.",
        "Disk full or allocation exceeded – No room in disk.",
        "Illegal TFTP operation – Unknown Opcode",
        "File already exists – File name exists on WRQ.",
        "User not logged in – Any opcode received before Login completes.",
        "User already logged in – Login username already connected." };

    

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {
        this.connections = connections;

        this.ID = connectionId;
        this.isLoggedIn = false;
        this.shouldTerminate = false;

        isCreatingFile = false;
        out = null;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    } 

    @Override
    public void process(byte[] message) {
        if(message.length < 2){
            ERROR(4); 
            return;
        }

        short OPCode = message[1];

        switch(OPCode){
            case RRQ:
                RRQ(message);
                break;
            case WRQ:
                WRQ(message);
                break;
            case DATA:
                DATA(message);
                break;
            case ACK:
                ACK(TftpEncoderDecoder.byteArrayToShort(Arrays.copyOfRange(message, 2, 4)));
                break;
            case ERROR:
                ERROR(message);
                break;
            case DIRQ:
                DIRQ(message);
                break;
            case LOGRQ:
                LOGRQ(message);
                break;
            case DELRQ:
                DELRQ(message);
                break;
            case BCAST:
                BCAST(message);
                break;
            case DISC:
                DISC(message);
                break;
            default:
                IlligalCommand(message);
        }
    }

    private boolean RRQ(byte[] msg){
        System.out.println("RRQ");

        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6);
            return false;
        }

        //check if the file name contain 0 byte
        byte[] cropped = Arrays.copyOfRange(msg, 2, msg.length-1);
        for(byte b : cropped){
            if(b == 0){  
                ERROR(0, "ERROR: Illigal file name, file name cannot contain '0' character!");
                return false;
            }
        }


        //get the file path (relative to base path)
        String relativePath = basePath + new String(Arrays.copyOfRange(msg, 2, msg.length-1), StandardCharsets.UTF_8);

        //get the file from the server
        
        try {
            File fileToRead = new File(relativePath);
            if(Files.exists(Paths.get(relativePath))){
                byte [] downloadedFile = Files.readAllBytes(fileToRead.toPath());
                byte [] OPcode = TftpEncoderDecoder.shortToByteArray(DATA);

                //concat both arrays, from SO: https://stackoverflow.com/questions/80476/how-can-i-concatenate-two-arrays-in-java
                byte[] data = Arrays.copyOf(OPcode, OPcode.length + downloadedFile.length);
                System.arraycopy(downloadedFile, 0, data, OPcode.length, downloadedFile.length);

                parseDataIntoPackets(data);

                return true;
            } else {
                ERROR(1);
                return false;
            }

        } catch (IOException e){
            ERROR(2);
            return false;
        }
    }

    private boolean WRQ(byte[] msg){
        System.out.println("WRQ");

        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6);
            return false;
        }

        byte[] fileNameBytes = Arrays.copyOfRange(msg, 2, msg.length-1);
        String fileName = new String(fileNameBytes, StandardCharsets.UTF_8);

        //check for invalid name
        if(fileName.length() == 0){
            ERROR(1, "ERROR: The file name can't be an empty string!");
            return false;
        }

        //check if the file name contain 0 byte
        byte[] cropped = Arrays.copyOfRange(msg, 2, msg.length-1);
        for(byte b : cropped){
            if(b == 0){  
                ERROR(0, "ERROR: Illigal file name, file name cannot contain '0' character!");
                return false;
            }
        }

        //check if the file already exists
        //File[] files = new File(basePath).listFiles();
        String fullRelativePath = basePath + fileName;
        File currentFile = new File(fullRelativePath);
        
        if(currentFile.exists()){    
            ERROR(5, "ERROR: the file already exists on the server!");
            return false;
        } 

        //check if another client is in the middle of creating this file
        if(filesGettingCreated.containsValue(fileName)){
            ERROR(5, "ERROR: another client is currently creating this file!");
            return false;
        }
            
        //set up for receving a file 
        try {
            out = new FileOutputStream(currentFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();

            ERROR(2);
        }
        
        
        isCreatingFile = true;
        filesGettingCreated.put(this.ID, fileName);

        ACK((short)00);
        return true;
    }

    private void DATA(byte[] msg){
        System.out.println("DATA");

        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6);
            return;
        }

        //parse the metadata
        short packetSize = TftpEncoderDecoder.byteArrayToShort(Arrays.copyOfRange(msg, 2, 4));
        short blockNumber = TftpEncoderDecoder.byteArrayToShort(Arrays.copyOfRange(msg, 4, 6));

        //check a case where the client keep trying to send data after a crush
        if(! isCreatingFile || out == null){
            ERROR(2);   //to make sure the client won't be deadlocked waiting for answer
            return;
        }

        //write the current data into the buffer
        try {
            byte[] cropped = Arrays.copyOfRange(msg, 6, msg.length);
            for(byte b : cropped)
                out.write(b);
            
            ACK(blockNumber);

        } catch (IOException e) {
            //If the writing process failed
            File fileToDelete = new File(filesGettingCreated.get(this.ID));
            fileToDelete.delete();

            isCreatingFile = false;
            out = null;
            filesGettingCreated.remove(this.ID);

            ERROR(blockNumber);   //to make sure the client won't be deadlocked waiting for answer
            return;
        }
        
        //if we have finished receiving the file
        if(packetSize != Packet.PACKET_MAX_SIZE){
            //finilazing the file
            try {
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
                ERROR(2);
            } finally{
                //mark the client as not writing file anymore
                String fileName = filesGettingCreated.get(this.ID);
                isCreatingFile = false;
                out = null;
                filesGettingCreated.remove(this.ID);

                BCAST(true, fileName); 
            }
        }
    }

    //@param block: block number, for packets other than data 0 = sucessful operation
    private void ACK(short block){

        byte[] OPCode = TftpEncoderDecoder.shortToByteArray((short)04);
        byte[] blockNumber = TftpEncoderDecoder.shortToByteArray(block);

        //concat both arrays, from SO: https://stackoverflow.com/questions/80476/how-can-i-concatenate-two-arrays-in-java
        byte[] data = Arrays.copyOf(OPCode, OPCode.length + blockNumber.length);
        System.arraycopy(blockNumber, 0, data, OPCode.length, blockNumber.length);

        //send the data to the parser and transfer it
        System.out.println("ACK " + block);
        parseDataIntoPackets(data);
    }

    //overloaded function
    private void ERROR(byte[] msg){
        short errorCode = msg[3];

        //check if there's an error message as well
        if(msg.length > 5){
            String errorMsg = new String(Arrays.copyOfRange(msg, 4, msg.length - 1), StandardCharsets.UTF_8);
            ERROR(errorCode, errorMsg);
        } else {
            ERROR(errorCode);
        }
    }


    private void ERROR(int errorCode){
        ERROR(errorCode, "ERROR: " + defaultErrorMessages[errorCode]);
    }
    
    private void ERROR(int errorCode, String str){
        short OPCode = 05;
        
        byte[] OPCodeBytes = TftpEncoderDecoder.shortToByteArray(OPCode);
        byte[] errorCodeBytes = TftpEncoderDecoder.shortToByteArray((short)errorCode);
        byte[] msgBytes = (str + (char)0).getBytes();

        Packet p = new Packet(OPCodeBytes.length + errorCodeBytes.length + msgBytes.length);
        p.continueWrite(OPCodeBytes);
        p.continueWrite(errorCodeBytes);
        p.continueWrite(msgBytes);

        System.out.println("ERROR " + errorCode + ": "+ str);
        parseDataIntoPackets(p.sendPacket());
    }

    private void DIRQ(byte[] msg){
        System.out.println("DIRQ");

        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6);
            return;
        }

        //get all the files' names as byte array
        File[] files = new File(basePath).listFiles();
        byte[][] filesAsBytes = new byte[files.length][];

        int totalLength = 0;

        //translate the file names to byte array
        for(int i=0; i<files.length; i++){
            filesAsBytes[i] =  (files[i].getName()).getBytes(); 
            totalLength += filesAsBytes[i].length + 1;  // +1 for the seperator 0
        }

        //concat all the names
        int index=0;
        byte[] combined = new byte[totalLength];
        for(int fileNumber=0; fileNumber<filesAsBytes.length; fileNumber++){
            for(int byteNumber = 0; byteNumber < filesAsBytes[fileNumber].length; byteNumber++){
                combined[index++] = filesAsBytes[fileNumber][byteNumber];
            }
            combined[index++] = (char)0;
        }

        //parse the combined names to make sure it abides the protocol
        byte[] opcode = {0,3};
        combined = Arrays.copyOfRange(combined, 0, Math.max(combined.length - 1, 0));   //remove the last 0, and take care of te case where are no files at all

        byte[] data = Arrays.copyOf(opcode, opcode.length + combined.length);
        System.arraycopy(combined, 0, data, opcode.length, combined.length);

        parseDataIntoPackets(data);
    }

    private boolean LOGRQ (byte[] msg){
        System.out.println("LOGRQ");

        //check if the user is already logged
        if(isLoggedIn){
            ERROR(7, "ERROR: you are already logged!");
            return false;
        }

        String username = new String(Arrays.copyOfRange(msg, 2, msg.length-1), StandardCharsets.UTF_8);

        //check if the username contain 0 byte
        byte[] cropped = Arrays.copyOfRange(msg, 2, msg.length - 1);
        for(byte b : cropped){
            if(b == 0){  
                ERROR(0, "ERROR: Illigal username, name cannot contain '0' character!");
                return false;
            }
        }

        //check if the username is free
        if (TftpProtocol.usernameMap.containsValue(username)){
            ERROR(7, "ERROR: username is already taken!");
            return false;
        } else {    //log the user into the server
            TftpProtocol.usernameMap.put(this.ID, username);
            isLoggedIn = true;
            ACK((short)00);
            return true;
        }

    }

    private void DELRQ(byte[] msg){
        System.out.println("DELRQ");
        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6, "ERROR: User is not logged in!");
            return;
        }

        String fileName = new String(Arrays.copyOfRange(msg, 2, msg.length-1), StandardCharsets.UTF_8);  
        String relativePath = basePath + fileName;

        //check if such file really exists
        File file = new File(relativePath); 

        if(Files.exists(Paths.get(relativePath))){
            //delete the file
            file.delete();
            ACK((short) 00);


            //send broadcast update
            BCAST(false, fileName);

        } else {
            ERROR(1);
        }
    }

    //overloading functions
    //@param: wasAdded is true iff an item was added
    //@Param: fileName is not null or empty string
    private void BCAST(boolean wasAdded, String fileName){

        if(fileName == null || fileName.length() == 0){
            ERROR(0, "ERROR: Illegal operation, tried broadcasting a change in a null or file with no name!");
        }


        byte[] fileNameBytes = fileName.getBytes();

        byte[] msg = new byte[2 + 1 + fileName.length() + 1];
        msg[0] = 0;
        msg[1] = BCAST;
        msg[2] = wasAdded ? (byte)1 : 0; 

        msg[msg.length-1] = 0;

        for(int i=3; i <= msg.length-2; i++){
            msg[i] = fileNameBytes[i-3];
        }

        BCAST(msg);
    }

    private void BCAST(byte[] msg){
        System.out.println("BCAST");

        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6, "ERROR: User is not logged in!");
            return;
        }

        //go over all the logged in users and send the a BCAST packet
        usernameMap.forEach((key, value) -> {
            try {
            connections.send(key, msg); 
            } catch (Exception e){ //in case a connection was forcefuy closed with CTRL+C
                usernameMap.remove(key);
                connections.disconnect(key);
            }
        });
        /* 
        for (Map.Entry<Integer, String> entry: usernameMap.entrySet()) 
            connections.send(entry.getKey(), msg);
        */
    }

    private void DISC(byte[] msg){
        System.out.println("DISC");

        //check if the user is already logged in
        if(! isLoggedIn){
            ERROR(6, "ERROR: User is not logged in!");
            return;
        }
        
        isLoggedIn = false;
        isCreatingFile = false;
        shouldTerminate = true;

        TftpProtocol.usernameMap.remove(this.ID);
        ACK((short)00);
        this.connections.disconnect(ID);
    }

    private void IlligalCommand(byte[] msg){
        System.out.println("IlligalCommand");
        ERROR(4, "ERROR: " + TftpEncoderDecoder.byteArrayToShort(Arrays.copyOfRange(msg, 0, 2))+ " is not a legal OPcode!");

        //send ACK to prevent deadlock
        ACK((short)0);
    }

    private void parseDataIntoPackets(byte[] rawData){
        short OPCode = TftpEncoderDecoder.byteArrayToShort(Arrays.copyOfRange(rawData, 0, 2));

        //if we're dealing with non-DATA packet we can send it in one go, so no need to parse and wrap the data
        if(OPCode != DATA){
            connections.send(this.ID, rawData);
            return;
        }

        //if we are dealing with DATA transfer
        byte[] data = Arrays.copyOfRange(rawData, 2, rawData.length);
        short block = 0;


        //get the bounds
        int lowerBound = block * Packet.PACKET_MAX_SIZE;
        int upperBound = Math.min((block + 1) * Packet.PACKET_MAX_SIZE, data.length);
        short packetSize = (short) (upperBound - lowerBound);   //its a safe convestion because the max value it can get is 512


        //this works because Arrays.copyOfRange exclude the upper index, so if the file size is an exact multiple of 512,
        //it will enter the loop one more time with no data to transfer, sending only the 6 bytes of metadata 

        while(packetSize >= 0 ){    
            //create new data packet
            Packet currenPacket = new Packet(packetSize + 6);
            //add metadata according to the protocol
            currenPacket.continueWrite(TftpEncoderDecoder.shortToByteArray(OPCode));
            currenPacket.continueWrite(TftpEncoderDecoder.shortToByteArray(packetSize));
            currenPacket.continueWrite(TftpEncoderDecoder.shortToByteArray((short)(block + 1)));
            //add the data itself
            byte[] dataToAdd = Arrays.copyOfRange(data, lowerBound, upperBound); 
            currenPacket.continueWrite(dataToAdd);
            //send the packet
            connections.send(this.ID, currenPacket.sendPacket());

            //update info for next packet
            block++;
            lowerBound = block * Packet.PACKET_MAX_SIZE;
            upperBound = Math.min((block + 1) * Packet.PACKET_MAX_SIZE, data.length);
            packetSize = (short) (upperBound - lowerBound);
        }
    }

    
}

/*
 *      The flow is as follows:
 *      C: WRQ
 *      S: ACK
 * 
 *      C: DATA 1
 *      S: ACK 1
 *      .
 *      .
 *      .
 *      C: DATA m
 *      S: ACK m
 * 
 *      -S create the file in the /Files/ folder-
 *      S: BCAST
 */