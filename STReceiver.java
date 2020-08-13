import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class STReceiver {
    private int queueSize = 10;
    private int tcpBuffer = 100;
    private int numberOfWrite = 2;
    private int sizeOfQueue = 100;
    private boolean debug = false;
    private int transferPort = 48892;
    private int maxBufferSize = 128*1024;
    private long throughputCycle = 1000l;
    private int clientPort = 53823;
    private String throughputMessage = "";
    private Boolean transferDone = false;
    private long numberOfMaxConnections = 0;
    private int numberOfReceive = 0;
    private AtomicLong totalWriteDone = new AtomicLong(0);
    private AtomicLong totalTransferDone = new AtomicLong(0);
    private long tillLastWrite = 0l;
    private long sinceLastTime = 0l;
    private long tillLastTransfer = 0l;
    private long startTime = 0l;
    private String status = "TRANSFER";
    private String toDir = "/data/hem/";
    private ExecutorService writePool = Executors.newFixedThreadPool(200);
    private HashMap<Integer, STReceiver.WriteBlock> writeBlocks = new HashMap<>();
    private ExecutorService receivePool = Executors.newFixedThreadPool(200);
    private HashMap<Integer, STReceiver.ReceiveFile> receiveBlocks = new HashMap<>();
    private static LinkedBlockingQueue<Block> blocks = new LinkedBlockingQueue<>(1500);
    private HashMap<String, RandomAccessFile> filesNames = new HashMap<>();

    private static void printUsage() {
        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        for (Method method : operatingSystemMXBean.getClass().getDeclaredMethods()) {
            method.setAccessible(true);
            if (Modifier.isPublic(method.getModifiers())) {
                    Object value;
                try {
                    value = method.invoke(operatingSystemMXBean);
                } catch (Exception e) {
                    value = e;
                } // try
                System.out.println(method.getName() + " = " + value);
            } // if
        } // for
    }

    public STReceiver(){

    }
    void startListeningToSender(){
        STReceiver.OpenTransferThread ott = this.new OpenTransferThread(this, this.transferPort);
        Thread tott = new Thread(ott, "0");
        tott.start();
    }
    public static void main(String[] args){
        STReceiver str = new STReceiver();
        STReceiver.TalkClient tc = str.new TalkClient(str);
        Thread ttc = new Thread(tc, "TalkClient");
        ttc.start();
        System.out.println("Started Receiver at "+str.clientPort+" port");
        long iter = 0l;
        long tillCertainTransfer = 0l;
        long lastCertainTime = 0l;
        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        Method my_method = null;
        for (Method method : operatingSystemMXBean.getClass().getDeclaredMethods()) {
            method.setAccessible(true);
            if (method.getName().startsWith("getProcessCpuLoad")
                && Modifier.isPublic(method.getModifiers())) {
                    Object value;
                try {
                    my_method = method;
                } catch (Exception e) {
                    value = e;
                } // try
            } // if
        } // for
        while(!str.transferDone){
            if(str.startTime != 0) {
                long totalTransfer = str.totalTransferDone.get();
                long lastTransfer = str.tillLastTransfer;
                str.tillLastTransfer = totalTransfer;
                long totalW = str.totalWriteDone.get();
                long lastW = str.tillLastWrite;
                str.tillLastWrite = totalW;

                long lastTime = str.sinceLastTime;
                str.sinceLastTime = System.currentTimeMillis();

                double timeInterval = str.sinceLastTime - lastTime;
                if(timeInterval>=1000) {
                    double thpt = 8 * 1000 * (totalW - lastW) / (timeInterval * 1000 * 1000);
                    double thpt_transfer = 8*1000*(totalTransfer-lastTransfer)/(timeInterval *1000*1000);
                    if (str.status.equalsIgnoreCase("probing") && (iter % 1) == 0) {
                        long thisTime = System.currentTimeMillis();
                        double avgThpt = Math.round(100.0*8*1000*(totalW-tillCertainTransfer)/((thisTime-lastCertainTime)*1000.*1000.))/100.0;

                        /*
                        System.out.println("Transfer Done till: " + totalW / (1024. * 1024.) +
                                "MB in time: " + (System.currentTimeMillis() - str.startTime) / 1000. +
                                " seconds and Throughput is:" + avgThpt + "Mbps time: " + timeInterval + " tnsferDone: " + (totalW-tillCertainTransfer) + " blocks: "+STReceiver.blocks.size());
                        //*/
                        System.out.println("Transfer Done till: " + totalW / (1024. * 1024.) + "MB in time: "+(System.currentTimeMillis() - str.startTime) / 1000. +
                                " seconds and thpt is: "+avgThpt+" Mbps"  + " blocks: "+STReceiver.blocks.size());
                        
                        // OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
                        // for (Method method : operatingSystemMXBean.getClass().getDeclaredMethods()) {
                        //     method.setAccessible(true);
                        //     if (Modifier.isPublic(method.getModifiers())) {
                        //             Object value;
                        //         try {
                        //             value = method.invoke(operatingSystemMXBean);
                        //         } catch (Exception e) {
                        //             value = e;
                        //         } // try
                        //         System.out.println(method.getName() + " = " + value);
                        //     } // if
                        // } // for
                        
                        tillCertainTransfer = totalW;
                        lastCertainTime = thisTime;
                    }


                    if (str.status.equalsIgnoreCase("probing") && tc.currentProbeStarted) {//PROBING
                        synchronized (str.throughputMessage) {
                            if (!str.throughputMessage.equalsIgnoreCase("")) {
                                str.throughputMessage += "," + (thpt*0.95+thpt_transfer*0.05);
                            } else {
                                str.throughputMessage += thpt;
                            }
                        }
                    }
                }
            }
            try{
                for(int i = 0; i < 100; i++) {
                    Thread.sleep(str.throughputCycle/100);
                }
            }catch(InterruptedException e){
                e.printStackTrace();
            }
            iter++;
        }

        System.out.println("[+] Receiver has stopped");
        try{
            Thread.sleep(5000);
        }catch(InterruptedException e){

        }
        System.exit(0);

    }
    public int getTransferPort(){
        return this.transferPort;
    }
    public void closeConnections(){
        for (int i = 0; i < this.receiveBlocks.size(); i++) {
            try {
                this.receiveBlocks.get(i).close();
            }catch (IOException e){}
        }
    }
    public void startWriteThreads(int count){
        this.stopAllWriteThreads();
        if(this.writeBlocks.size() < count){
            for(int i=this.writeBlocks.size(); i<count; i++){
                STReceiver.WriteBlock rb = this.new WriteBlock(this);
                rb.stopThread();
                String threadName = ""+i;
                Thread trb = new Thread(rb, threadName);
                this.writePool.execute(trb);
                this.writeBlocks.put(i, rb);
            }
        }
        for(int i=0; i<count;i++){
            this.writeBlocks.get(i).startThread();
        }
    }
    public void stopAllWriteThreads(){
        for (int i = 0; i < this.writeBlocks.size(); i++) {
            this.writeBlocks.get(i).stopThread();
        }
    }
    public void stopEverything(){
        for (int i = 0; i < this.receiveBlocks.size(); i++) {
            this.receiveBlocks.get(i).stopThread();
        }
        this.stopAllWriteThreads();
    }
    public void transferIsDone(){
        this.stopEverything();
        this.receivePool.shutdown();
        this.writePool.shutdown();
    }
    public void setTCPBuffer(int count){
        for(int i=this.receiveBlocks.size(); i<count; i++){
            try {
                while (this.receiveBlocks.get(i).socket_receive == null){
                    Thread.sleep(10);
                }
                this.receiveBlocks.get(i).socket_receive.setReceiveBufferSize(this.tcpBuffer);
            }catch(InterruptedException e){

            }catch (IOException ee) {

            }
        }
        
    }
    public void startProbing(){
        this.startWriteThreads(this.numberOfWrite);
        this.setTCPBuffer(this.numberOfReceive);
    }
    class OpenTransferThread implements Runnable{
        STReceiver stReceiver = null;
        int communicationPort = 0;

        OpenTransferThread(STReceiver str, int port){
            this.stReceiver = str;
            this.communicationPort = port;
        }

        @Override
        public void run() {
            if(this.stReceiver.debug) {
                System.out.println("[+] Open Transfer Thread-" + Thread.currentThread().getName() + " has Started.");
            }
            try {
                if(this.stReceiver.receiveBlocks == null){
                    this.stReceiver.receiveBlocks = new HashMap<>();
                }
                ServerSocket socketReceive = new ServerSocket(this.communicationPort);
                Socket clientSock = socketReceive.accept();
                DataInputStream dataInputStream  = new DataInputStream(clientSock.getInputStream());
                DataOutputStream dos = new DataOutputStream(clientSock.getOutputStream());
                this.stReceiver.numberOfMaxConnections = dataInputStream.readLong();
                this.stReceiver.startTime = System.currentTimeMillis();
                int startPort = 61024;
                for(int i=startPort;i<this.stReceiver.numberOfMaxConnections+startPort;i++) {
                    STReceiver.ReceiveFile rf = this.stReceiver.new ReceiveFile(this.stReceiver, i);
                    this.stReceiver.receiveBlocks.put(i - startPort, rf);
                    String threadName = "" + (i - startPort);
                    Thread trf = new Thread(rf, threadName);
                    this.stReceiver.receivePool.execute(trf);
                }
                try{
                    Thread.sleep(100);
                }catch(InterruptedException e){}

                for(int i=startPort;i<this.stReceiver.numberOfMaxConnections+startPort;i++){
                    dos.writeInt(i);
                }
            }catch(IOException e){
                e.printStackTrace();
            }

        }
    }
    class Block{
        long offset = 0l;
        long length = 0l;
        String filename = "";
        boolean written = false;
        long fileId = 0l;
        long fileLength = 0l;
        long blockId = 0l;
        List<Buffer> byteArray;
        long tillNow = 0l;
        boolean bufferLoaded = false;
        long startTime = 0l;


        Block(long offset, long length){
            this.offset = offset;
            this.length = length;
            byteArray = new ArrayList<Buffer>();

        }
        void add_buffer(byte[] bff, int buffer_size){
            byteArray.add(new Buffer(bff, buffer_size));
            tillNow += buffer_size;

        }
        void remove_buffer(){
            this.byteArray = null;
        }
        void setOffset(long offset){this.offset=offset;}
        void setFilename(String fn){this.filename=fn;}
        void setFileId(long fi){this.fileId=fi;}
        void setBlockId(long bi){this.blockId=bi;}
    }
    class Buffer {
        byte[] small_buffer;
        int length;

        Buffer(byte[] buffer, int buffer_size){
            small_buffer = Arrays.copyOf(buffer, buffer_size);
            length = buffer_size;
        }
    }
    class WriteBlock implements Runnable{
        STReceiver stReceiver = null;
        boolean waitNext = false;
        WriteBlock(STReceiver str){
            this.stReceiver = str;
        }
        void stopThread(){this.waitNext = true;}
        void startThread(){this.waitNext=false;}

        @Override
        public void run() {
            if(this.stReceiver.debug) {
                System.out.println("[+] Write Thread-" + Thread.currentThread().getName() + " has Started.");
            }
            //Start reading block and write to this.stSender.blocks
            while(true){
                Block currentBlock = null;
                while(STReceiver.blocks.isEmpty()){
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                try {
                    currentBlock = STReceiver.blocks.poll(50, TimeUnit.MILLISECONDS);
                    if(currentBlock == null) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        if(this.stReceiver.transferDone &&
                                STReceiver.blocks.size() == 0){
                            break;
                        }
                        continue;
                    }
                    long st_wite = System.currentTimeMillis();
                    String filename = this.stReceiver.toDir + "/" + currentBlock.filename;
                    RandomAccessFile randomAccessFile = filesNames.remove(filename);
                    if(randomAccessFile == null) {
                        randomAccessFile = new RandomAccessFile(filename, "rw");
                    }
                    if (currentBlock.offset > 0) {
                        randomAccessFile.getChannel().position(currentBlock.offset);
                    }
                    for(Buffer buffer: currentBlock.byteArray){
                        randomAccessFile.write(buffer.small_buffer, 0, buffer.length);

                        buffer.small_buffer = null;
                    }
                    if(this.stReceiver.filesNames.containsKey(filename)){
                        randomAccessFile.close();
                    }else {
                        filesNames.put(filename, randomAccessFile);
                    }

                    long done_wrt = System.currentTimeMillis();
                    //System.out.println("[+] Block is done of blockId"+currentBlock.blockId+ " in total time "+(done_wrt-currentBlock.startTime)+"ms & and write time "+(done_wrt-st_wite)+"ms total bolcks is "+STReceiver.blocks.size());

                    if(this.stReceiver.debug){
                        System.out.println("[Block Written "+System.currentTimeMillis()+"] fileId: "+currentBlock.fileId+" blockId: "+currentBlock.blockId + " offset: "+currentBlock.offset +
                                " threadName:"+Thread.currentThread().getName());
                    }
                    currentBlock = null;
                }catch(Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
    class ReceiveFile implements Runnable{
        STReceiver stReceiver = null;
        boolean waitNext = false;
        boolean closeConnection = false;
        Socket clientSock = null;
        ServerSocket socket_receive = null;
        int sendFileId = 0;
        Socket s = null;
        int port = 0;

        DataInputStream dataInputStream = null;

        ReceiveFile(STReceiver str, int port){
            //Start a connection here
            this.stReceiver = str;
            this.port = port;
        }
        void stopThread(){this.waitNext = true;}
        void startThread(){this.waitNext=false;}
        void close() throws IOException{
            if(clientSock!=null){
                clientSock.close();
            }
            if(socket_receive!=null){
                socket_receive.close();
            }
        }
        @Override
        public void run() {
            if (this.stReceiver.debug) {
                System.out.println("[+] Receive Thread-" + Thread.currentThread().getName() + " has Started.");
            }
            try {
                socket_receive = new ServerSocket(port);
                // socket_receive.setReceiveBufferSize(32*1024*1024);
                clientSock = socket_receive.accept();

                dataInputStream = new DataInputStream(clientSock.getInputStream());
                while (!closeConnection) {
                    while (STReceiver.blocks.remainingCapacity() == 0) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    try {
                        int read = 0;
                        String filename = dataInputStream.readUTF();
                        if (filename.equals("done")) {
                            break;
                        }
                        long length = dataInputStream.readLong();
                        long offset = dataInputStream.readLong();
                        long fileId = dataInputStream.readLong();
                        long blockId = dataInputStream.readLong();

                        Block currentBlock = new Block(offset, length);
                        currentBlock.startTime = System.currentTimeMillis();
                        currentBlock.setFilename(filename);
                        currentBlock.setFileId(fileId);
                        currentBlock.setBlockId(blockId);
                        if(this.stReceiver.debug) {
                            System.out.println("[Block Received "+System.currentTimeMillis()+" Started] " + currentBlock.filename + " fileId: " + currentBlock.fileId + " blockId: " + currentBlock.blockId +
                                    " offset: " + currentBlock.offset + " length: " + currentBlock.length + " blockLoaded: " + currentBlock.written);
                        }
                        byte[] buffer = new byte[maxBufferSize];


                        while (currentBlock.tillNow < currentBlock.length) {
                            //long st_tim = System.currentTimeMillis();
                            read = dataInputStream.read(buffer, 0, (int) Math.min(buffer.length, currentBlock.length - currentBlock.tillNow));
                            totalTransferDone.addAndGet(read);
                            //long new_st_tim = System.currentTimeMillis();
                            currentBlock.add_buffer(buffer, read);
                            totalWriteDone.addAndGet(read);
                            //totalWriteDone.addAndGet(read);
                            //long latest_st = System.currentTimeMillis();
                            //System.out.println("Receive Blockid = "+currentBlock.blockId+" and read time "+ (new_st_tim-st_tim)+"ms and add time "+(latest_st-new_st_tim)+"ms");
                        }
                        boolean doneAddition = true;

                        if(this.stReceiver.debug) {
                            System.out.println("[Block Received "+System.currentTimeMillis()+" Done] " + currentBlock.filename + " fileId: " + currentBlock.fileId + " blockId: " + currentBlock.blockId +
                                    " offset: " + currentBlock.offset + " length: " + currentBlock.length + " blockLoaded: " + currentBlock.written);
                        }
                        currentBlock.written = true;

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
//                this.stReceiver.transferDone = true;
            }catch(IOException e){
                e.printStackTrace();
            }
        }
    }
    class TalkClient implements Runnable{
        private Socket talkSocket = null;
        private ServerSocket serverSocket = null;
        private STReceiver stReceiver = null;


        BufferedReader readFromClient = null;
        PrintStream sendClient = null;
        boolean stopProbing = false;
        boolean startCurrentProbing = false;
        boolean stopCurrentProbing = false;
        boolean currentProbeStarted = false;


        public TalkClient(STReceiver str){
            this.stReceiver = str;
        }
        String parseMessage(String message){
            String[] messages = message.split(":");
            if(messages[0].equalsIgnoreCase("start")){
                if(messages[1].equalsIgnoreCase("transfer")){
                    this.stReceiver.status = "TRANSFER";
                    System.out.println("[+] Normal transfer has started");
                    this.stReceiver.startProbing();
                    return "ok";
                }else if(messages[1].equalsIgnoreCase("probing")){
                    this.stReceiver.status = "PROBING";
                    return "ok";
                }else if(messages[1].equalsIgnoreCase("currentProbing")){
                    this.stReceiver.status = "PROBING";
                    this.stReceiver.startProbing();
                    this.currentProbeStarted = true;
                    this.stReceiver.sinceLastTime = System.currentTimeMillis();
                    return "ok";
                }
            }else if(messages[0].equalsIgnoreCase("stop")){
                if(messages[1].equalsIgnoreCase("currentProbing")){
                    this.currentProbeStarted = false;
                    this.stReceiver.stopEverything();
                    return "ok";
                }else if(messages[1].equalsIgnoreCase("everything")){
                    return "ok";
                }
            }else if(messages[0].equalsIgnoreCase("parameter")){
                System.out.println("Parameter: Done");
                while(STReceiver.blocks.size() !=0){
                    try{
                        Thread.sleep(10);
                    }catch(InterruptedException e){ }
                }
                String[] params = messages[1].split(",");
                this.stReceiver.sizeOfQueue = queueSize;//Integer.parseInt(params[5]);
                this.stReceiver.numberOfReceive = Integer.parseInt(params[4]);
                this.stReceiver.tcpBuffer = Integer.parseInt(params[5]) * 1024;
                ///*
                synchronized (STReceiver.blocks){
                    STReceiver.blocks = new LinkedBlockingQueue<>(this.stReceiver.sizeOfQueue);
                }
                //*/
                this.stReceiver.numberOfWrite = Integer.parseInt(params[6]);
                System.out.println("Parameter: "+messages[1]);
                return "ok";
            }else if(messages[0].equalsIgnoreCase("get throughput")){
                String thpts = "";
                synchronized (this.stReceiver.throughputMessage){
                    thpts = this.stReceiver.throughputMessage;
                    this.stReceiver.throughputMessage = "";
                }
                return thpts;
            }else if(messages[0].equalsIgnoreCase("done")){
                while(STReceiver.blocks.size() != 0){
                    try{
                        Thread.sleep(10);
                    }catch(InterruptedException e){}
                }
                System.out.println("Receiver is done");
                System.out.println("Transfer should be done");
                this.stReceiver.transferIsDone();
                this.stopProbing = true;
//                double write_done = this.stReceiver.totalWriteDone.get() / 1000000000;
//                double thpt = Math.ceil(write_done) * 1.024*1.024*8;
                System.out.println(this.stReceiver.totalWriteDone);
                this.stReceiver.transferDone = true;
                return ":ok:"+this.stReceiver.totalWriteDone;
            }
            return "ok";
        }
        public void run(){
            try {
                this.serverSocket = new ServerSocket(this.stReceiver.clientPort);
                this.talkSocket = serverSocket.accept();

                this.readFromClient= new BufferedReader(new InputStreamReader(this.talkSocket.getInputStream()));
                this.sendClient = new PrintStream(this.talkSocket.getOutputStream());

                this.stReceiver.toDir = this.readFromClient.readLine().trim();
                this.stReceiver.receivePool = Executors.newFixedThreadPool(Integer.parseInt(this.readFromClient.readLine().trim()));
                this.stReceiver.writePool = Executors.newFixedThreadPool(Integer.parseInt(this.readFromClient.readLine().trim()));
                this.stReceiver.blocks = new LinkedBlockingQueue<>(Integer.parseInt(this.readFromClient.readLine().trim()));
                this.stReceiver.maxBufferSize = Integer.parseInt(this.readFromClient.readLine().trim());
                this.stReceiver.startListeningToSender();
                this.sendMessage(""+this.stReceiver.getTransferPort());

                //String init_ = this.readFromClient.readLine();
                //this.sendMessage("ok");

                while(!this.stopProbing){
                    String receivedMessage = this.readFromClient.readLine();
                    String message = this.parseMessage(receivedMessage);
                    this.sendMessage(message);
                }
                try{
                    Thread.sleep(1000);
                }catch(InterruptedException e){

                }
                this.close();
            }catch(IOException e){
                e.printStackTrace();
            }
        }
        void sendMessage(String message){
            this.sendClient.println(message);
        }
        public void close() throws IOException{
            talkSocket.close();
            readFromClient.close();
            sendClient.close();
        }
    }
}
