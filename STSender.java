import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Queue;
import java.util.concurrent.*;

public class STSender {
    private String receiverIP = "192.168.1.1";
    private int receiverPort = 48892;
    int numberOfRead = 2;
    private int sizeOfQueue = 100;
    private int numberOfConnection = 1;
    private int numberOfMaxConnections = 10;
    int clientPort = 52005;
    private long blockSize = 256*1024*1024l;
    private long bufferSize = 128*1024l;
    private boolean debug = false;
    String status = "TRANSFER";
    private Boolean transferDone = false;
    private String fromDir = "/data/aalhussen/10Mfiles";
    private static Queue<TransferFile> files = new LinkedList<TransferFile>();//Collections.asLifoQueue(new ArrayDeque<>());
    private ExecutorService readPool = Executors.newFixedThreadPool(200);
    private HashMap<Integer, STSender.ReadBlock> readBlocks = new HashMap<>();
    private ExecutorService sendPool = Executors.newFixedThreadPool(200);
    private HashMap<Integer, STSender.SendFile> sendBlocks = new HashMap<>();
    private static LinkedBlockingQueue<Block> blocks = new LinkedBlockingQueue<>(1500);
    private boolean sendingDone = false;
    private boolean fileFinished = false;
    private static Long totalByteToSend = 0l;
    private long last_file_id = 0l;
    private long total_files = 0l;
    private STSender.OpenTransferThread ott = null;
    private HashMap<String, List<FileInputStream>> openFiles = new HashMap<>();

    static int fileNum = 0;

    public STSender(){

    }
    public static void main(String[] args){
        STSender sts = new STSender();
        STSender.TalkClient tc = sts.new TalkClient(sts);
        Thread ttc = new Thread(tc, "TalkClient");
        ttc.start();
        try {
            ttc.join();
        }catch(InterruptedException e){

        }
        System.out.println("done");
        try{
            Thread.sleep(15000);
        }catch(InterruptedException e){

        }
        System.exit(0);
    }
    void talkWithReceiver(STSender sts){
        ott = sts.new OpenTransferThread(sts, sts.receiverIP, sts.receiverPort);
        Thread tott = new Thread(ott, "0");
        tott.start();
    }
    public void startReadThreads(int count){
        this.stopAllReadThreads();
        if(this.readBlocks.size() < count){
            for(int i=this.readBlocks.size(); i<count; i++){
                STSender.ReadBlock rb = this.new ReadBlock(this);
                rb.stopThread();
                String threadName = ""+i;
                Thread trb = new Thread(rb, threadName);
                this.readPool.execute(trb);
                this.readBlocks.put(i, rb);
            }
        }
        for(int i=0; i<count;i++){
            this.readBlocks.get(i).startThread();
        }
    }
    public void closeConnections(){
        for (int i = 0; i < this.sendBlocks.size(); i++) {
            try {
                this.sendBlocks.get(i).close();
            }catch (IOException e){}
        }
    }
    public void stopAllReadThreads(){
        for (int i = 0; i < this.readBlocks.size(); i++) {
            this.readBlocks.get(i).stopThread();
        }
    }
    public void startProbing(){
        this.startReadThreads(this.numberOfRead);
        this.startSendThreads(this.numberOfConnection);
//        System.out.println("Current Probing started\n");
    }
    public void stopProbing(){
        this.stopAllReadThreads();
        this.stopAllSendThreads();
//        System.out.println("Current Probing stopped\n");
    }
    public void startSendThreads(int count){
        this.stopAllSendThreads();
        System.out.println("" + count + " and the total send threads are "+this.sendBlocks.size());
        if(count <= this.sendBlocks.size()) {
            for (int i = 0; i < count; i++) {
                this.sendBlocks.get(i).startThread();
            }
        }
    }
    public void transferIsDone(){
        this.stopProbing();
        this.readPool.shutdown();
        this.sendPool.shutdown();
    }
    public void stopAllSendThreads(){
        for (int i = 0; i < this.sendBlocks.size(); i++) {
            this.sendBlocks.get(i).stopThread();
        }
    }

    class OpenTransferThread implements Runnable{
        STSender stSender = null;
        int communicationPort = 0;
        Socket s = null;
        String receiverIp = "";
        boolean stopThreads = false;

        OpenTransferThread(STSender sts, String receiverIp, int port){
            this.stSender = sts;
            this.communicationPort = port;
            this.receiverIp = receiverIp;
        }
        void stopThread(){this.stopThreads = true;}
        void startThread(){this.stopThreads=false;}

        @Override
        public void run() {
            if(this.stSender.debug) {
                System.out.println("[+] Open Transfer Thread-" + Thread.currentThread().getName() + " has Started.");
                System.out.println("IP: "+this.receiverIp + "\t and Port: "+this.communicationPort);
            }
            try {
                s = new Socket(receiverIp, this.communicationPort);
                DataOutputStream dos = new DataOutputStream(s.getOutputStream());
                dos.writeLong((long)this.stSender.numberOfMaxConnections);
                DataInputStream isr = new DataInputStream(s.getInputStream());
                for(int i =0;i<this.stSender.numberOfMaxConnections;i++){
                    int port = isr.readInt();
                    try {
                        STSender.SendFile sf = this.stSender.new SendFile(this.stSender, this.receiverIp, port);
                        this.stSender.sendBlocks.put(i, sf);
                        String threadName = ""+i;
                        Thread tsf = new Thread(sf, threadName);
                        this.stSender.sendPool.execute(tsf);
                    }catch(Exception e){
                        e.printStackTrace();
                    }
                }
                while(!this.stopThreads){
                    try{
                        Thread.sleep(100);
                    }catch(InterruptedException e){
                        e.printStackTrace();
                    }
                }
                dos.writeUTF("exit");
                String lastMessage = isr.readUTF();
                if(lastMessage!= null && lastMessage.trim().equalsIgnoreCase("done")){
                }
            }catch(IOException e){
                e.printStackTrace();
            }
        }
    }
    class SendFile implements Runnable{
        STSender stSender = null;
        boolean waitNext = false;
        boolean closeConnection = false;
        DataOutputStream dos = null;
        int sendFileId = 0;
        Socket s = null;
        String receiverIp = "";
        int port = 0;

        SendFile(STSender sts, String receiverIp, int port) throws UnknownHostException, IOException{
            //Start a connection here
            this.stSender = sts;
            this.receiverIp = receiverIp;
            this.port = port;
        }
        void stopThread(){
            this.waitNext = true;
//            System.out.println("[-] Thread is stopped again");
        }
        void startThread(){
            this.waitNext=false;
//            System.out.println("[+] Thread is started again");
        }
        void close() throws IOException{
            if(s!=null){
                s.close();
            }
            if(dos!=null){
                dos.close();
            }
        }
        @Override
        public void run() {
            if(this.stSender.debug) {
                System.out.println("[+] Send Thread-" + Thread.currentThread().getName() + " has Started.");
            }
            try {
                s = new Socket(this.receiverIp, this.port);
                dos = new DataOutputStream(s.getOutputStream());
                while (!closeConnection) {
                    Block currentBlock = null;
                    ///*
                    while (this.waitNext) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }//*/
//                    System.out.println("Block Sending Starting");
                    synchronized (STSender.blocks) {
                        if (!STSender.blocks.isEmpty()) {
                            currentBlock = STSender.blocks.poll();
                            STSender.fileNum++;
                        }else if(this.stSender.fileFinished){
                            this.stSender.sendingDone = true;
                        }
                    }
                    try {
                        if (currentBlock == null || !currentBlock.bufferLoaded) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            continue;
                        }
                        dos.writeUTF(currentBlock.filename);
                        dos.writeLong(currentBlock.length);
                        dos.writeLong(currentBlock.offset);
                        dos.writeLong(currentBlock.fileId);
                        dos.writeLong(currentBlock.blockId);


                        for (Buffer buffer : currentBlock.byteArray) {
                            //long st_tim = System.currentTimeMillis();
                            dos.write(buffer.small_buffer, 0, buffer.length);
                            //long new_st_tim = System.currentTimeMillis();
                            buffer.small_buffer = null;
                            //long latest_st = System.currentTimeMillis();
                            //System.out.println("Sent Blockid = "+currentBlock.blockId+" and send time "+ (new_st_tim-st_tim)+"ms and add time "+(latest_st-new_st_tim)+"ms");
                        }
                        //System.out.println("[Block Sent] fileId: " + currentBlock.fileId + " Blockid: " + currentBlock.blockId + " total queue size "+STSender.blocks.size());
                        if (this.stSender.debug) {
                            System.out.println("[Block Sent "+System.currentTimeMillis()+"] fileId: " + currentBlock.fileId + " Blockid: " + currentBlock.blockId);
                        }
                        currentBlock = null;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                this.stSender.transferDone = true;
            }catch(ConnectException ee){
                System.out.println("[-] Connection refused by the receiver at port: "+this.port);

            }catch(IOException e){
                e.printStackTrace();
            }
        }
    }
    class ReadBlock implements Runnable{
        STSender stSender = null;
        long buffer_size = 128*1024l;
        boolean waitNext = false;
        ReadBlock(STSender sts){
            this.stSender = sts;
            this.buffer_size = this.stSender.bufferSize;
        }
        void stopThread(){this.waitNext = true;}
        void startThread(){this.waitNext=false;}

        @Override
        public void run() {
            //Start reading block and write to this.stSender.blocks
            if(this.stSender.debug) {
                System.out.println("[+] Read Thread-" + Thread.currentThread().getName() + " has Started.");
            }
            while(true){
                TransferFile head = null;
                Block currentBlock = null;
                while(waitNext || STSender.blocks.size() >= this.stSender.sizeOfQueue){
                    try {
                        Thread.sleep(10);
                        continue;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                long newOffset = 0;
                long length = 0;
                long blockId = 0;
                int n;
                FileInputStream fis = null;
                synchronized (STSender.files) {
                    if(STSender.files.size()==0){
                        this.stSender.fileFinished = true;
                    }
                    head = STSender.files.poll();
                    if(head!=null){
                        blockId = head.blockId;
                        head.blockId++;
                        newOffset = head.offset;
                        length = Math.min(this.stSender.blockSize, head.length - newOffset);
                        head.offset += length;
                        if (head.offset < head.length) {
                            STSender.files.add(head);
                        }
                        /*
                        else if (this.stSender.last_file_id < this.stSender.total_files * 3.5){
                            STSender.files.add(new TransferFile(head.file, head.filename, 0l, head.length, this.stSender.last_file_id++));
                            STSender.totalByteToSend += head.length;
                        }
                        //*/
                    }
                }
                try {
                    if(head == null) {
                        if(STSender.files.size() == 0) {
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        continue;
                    }
                    currentBlock = new Block(newOffset, length);
                    currentBlock.filename = head.filename;
                    currentBlock.fileId = head.fileId;
                    currentBlock.blockId = blockId;
                    currentBlock.fileLength = head.length;

                    byte[] buffer = new byte[(int)buffer_size];

                    if(this.stSender.debug) {
                        System.out.println("[Block Load "+System.currentTimeMillis()+" started] "+currentBlock.filename + " fileId: " + currentBlock.fileId + " blockId: " + currentBlock.blockId +
                                " offset: " + currentBlock.offset + " length: " + currentBlock.length + " fileLength: " +
                                currentBlock.fileLength + " blockLoaded: "+ currentBlock.bufferLoaded + " threadName: "+Thread.currentThread().getName());
                    }
                    while ((int) Math.min(buffer.length, currentBlock.length - currentBlock.tillNow) > 0) {
                        n = (int) Math.min(buffer.length, currentBlock.length - currentBlock.tillNow);
                        currentBlock.add_buffer(buffer, n);
                        if (currentBlock.tillNow >= currentBlock.length) {
                            boolean isSuccess = false;
                            while (!isSuccess) {
                                isSuccess =  STSender.blocks.offer(currentBlock, 100, TimeUnit.MILLISECONDS);
                            }
                            currentBlock.bufferLoaded = true;
                            if(this.stSender.debug) {
                                System.out.println("[Block Loaded "+System.currentTimeMillis()+"] "+currentBlock.filename + " fileId: " + currentBlock.fileId + " blockId: " + currentBlock.blockId +
                                        " offset: " + currentBlock.offset + " length: " + currentBlock.length + " fileLength: " +
                                        currentBlock.fileLength + " blockLoaded: "+ currentBlock.bufferLoaded + " threadName: "+Thread.currentThread().getName());
                            }
                        }
                    }
                    //Might need to initiate buffer to null and to buffer_size again
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        if (fis != null) {
                            fis.close();
                        }
                    }catch(IOException ee){
                        ee.printStackTrace();
                    }
                    if(currentBlock!=null && head!=null){
                        synchronized (STSender.files){
                            //File file, String filename, long offset, long length, long fileId
                            STSender.files.add(new TransferFile(head.file, currentBlock.filename, currentBlock.offset, currentBlock.length, currentBlock.fileId));
                            currentBlock.bufferLoaded = false;
                        }
                    }
                    break;
                }
            }
            System.out.println("Blocks in blocks: "+STSender.blocks.size());
        }
    }
    class Block{
        long offset = 0l;
        long length = 0l;
        String filename = "";
        long fileId = 0l;
        long fileLength = 0l;
        boolean written = false;
        long blockId = 0l;
        List<Buffer> byteArray;
        long tillNow = 0l;
        boolean bufferLoaded = false;


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
    void collect_files(){
        File file = new File(this.fromDir);
        long fileId = 0l;
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                long fileLeng = f.length();
                STSender.totalByteToSend += fileLeng;
                STSender.files.add(new TransferFile(f, f.getName(), 0, f.length(), fileId));
                this.openFiles.put(this.fromDir + "/" + f.getName(), new ArrayList<>());
                fileId++;
            }
        } else {
            long fileLeng = file.length();
            STSender.totalByteToSend += fileLeng;
            STSender.files.add(new TransferFile(file, file.getName(), 0, file.length(), fileId));
            this.fromDir = file.getParent();
            this.openFiles.put(this.fromDir + "/" + file.getName(), new ArrayList<>());
            fileId++;
        }
        this.last_file_id = fileId;
        this.total_files = fileId - 1;
        System.out.println("[+] Sending "+files.size()+" files to receiver.");
        System.out.println("[+] Sending "+Math.round(100*STSender.totalByteToSend/(1024.0*1024*1024.))/100.0+" GB data to receiver.");

    }
    class TransferFile{
        String filename = "";
        long offset = 0l;
        long length = 0l;
        long fileId = 0l;
        File file = null;
        long blockId = 0l;
        TransferFile(File file, String filename, long offset, long length, long fileId){
            this.file = file;
            this.filename = filename;
            this.offset = offset;
            this.length = length;
            this.fileId = fileId;
        }
    }


    class TalkClient implements Runnable{
        private Socket talkSocket = null;
        private ServerSocket serverSocket = null;
        private STSender stSender = null;


        BufferedReader readFromClient = null;
        PrintStream sendClient = null;
        boolean stopProbing = false;


        public TalkClient(STSender sts){
            this.stSender = sts;
        }
        String parseMessage(String message){
            String[] messages = message.split(":");
            if(messages[0].equalsIgnoreCase("start")){
                if(messages[1].equalsIgnoreCase("transfer")){
                    this.stSender.status = "TRANSFER";
                    return "ok";
                }else if(messages[1].equalsIgnoreCase("probing")){
                    this.stSender.status = "PROBING";
                    return "ok";
                }else if(messages[1].equalsIgnoreCase("currentprobing")){
                    this.stSender.startProbing();
                    return "ok";
                }
            }else if(messages[0].equalsIgnoreCase("stop")){
                if(messages[1].equalsIgnoreCase("currentprobing")){
                    this.stSender.stopProbing();
                    return "ok";
                }
            }else if(messages[0].equalsIgnoreCase("parameter")){
//                while(STSender.blocks.size() !=0){
//                    try{
//                        Thread.sleep(100);
//                    }catch(InterruptedException e){
//
//                    }
//                }
                String[] params = messages[1].split(",");

                this.stSender.numberOfRead = Integer.parseInt(params[0]);
                this.stSender.sizeOfQueue = Integer.parseInt(params[1]);

//                synchronized (STSender.blocks){
//                    STSender.blocks = new LinkedBlockingQueue<>(this.stSender.sizeOfQueue);
//                }
                this.stSender.blockSize = Long.parseLong(params[2])*1024*1024;
                this.stSender.bufferSize = Long.parseLong(params[3])*1024;
                this.stSender.numberOfConnection = Integer.parseInt(params[4]);
                return "ok";
            }else if(messages[0].equalsIgnoreCase("check")){
                if(messages[1].equalsIgnoreCase("done")){
                    if(STSender.files.size() == 0 && STSender.blocks.size()==0 && this.stSender.sendingDone){
                        //System.out.println("[+] Sender is done");
                        this.stSender.transferIsDone();
                        this.stopProbing = true;
                        this.stSender.ott.stopThreads = true;
                        return "true:"+STSender.totalByteToSend;
                    }
                }
            }
            return "ok";
        }
        public void run(){
            try {
                this.serverSocket = new ServerSocket(stSender.clientPort);
                System.out.println("Listening to port "+stSender.clientPort);
                this.talkSocket = serverSocket.accept();
                System.out.println("Socket accepted");

                this.readFromClient= new BufferedReader(new InputStreamReader(this.talkSocket.getInputStream()));
                this.sendClient = new PrintStream(this.talkSocket.getOutputStream());
                this.stSender.receiverIP = this.readFromClient.readLine().trim();
                System.out.println(this.stSender.receiverIP);
                this.stSender.receiverPort = Integer.parseInt(this.readFromClient.readLine());

                this.stSender.fromDir = this.readFromClient.readLine().trim();
                this.stSender.collect_files();
                this.stSender.numberOfMaxConnections = Integer.parseInt(this.readFromClient.readLine().trim());
                this.stSender.sendPool = Executors.newFixedThreadPool(this.stSender.numberOfMaxConnections);
                this.stSender.readPool = Executors.newFixedThreadPool(Integer.parseInt(this.readFromClient.readLine().trim()));
                this.stSender.blocks = new LinkedBlockingQueue<>(Integer.parseInt(this.readFromClient.readLine().trim()));
                this.stSender.talkWithReceiver(this.stSender);

                while(!this.stopProbing){
                    String receivedMessage = this.readFromClient.readLine();
//                    System.out.println("[+] Message is "+receivedMessage);
                    String message = this.parseMessage(receivedMessage);
                    this.sendMessage(message);
                }
                try{
                    Thread.sleep(2000);
                }catch(InterruptedException e){

                }
                this.close();
            }catch(IOException e){
                e.printStackTrace();
            }
            System.out.println("TalkClient thread done");
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
