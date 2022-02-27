/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package Server;


import static Server.Server.db;
import models.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.sql.SQLException;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ServerHandler extends Thread {

    DataInputStream inputStream;
    PrintStream printStream;
    public Person loggedPlayer;
    public static Vector<ServerHandler> handlers = new Vector<ServerHandler>();
    static Vector<Person> allPlayers = new Vector<Person>();
    public ServerHandler(Socket clientSocket) throws IOException {
        allPlayers = Server.players;
        inputStream = new DataInputStream(clientSocket.getInputStream());
        printStream = new PrintStream(clientSocket.getOutputStream());

        handlers.add(this);
        start();

    }

    public void run() {
        while (true) {
            try {
                JSONObject msg = new JSONObject(inputStream.readLine()) ;
                processMessage(msg);            
            } catch (IOException ex) {
                
                try {
                    closeConnection();
                    break;
                } catch (JSONException ex1) {
                    Logger.getLogger(ServerHandler.class.getName()).log(Level.SEVERE, null, ex1);
                } catch (SQLException ex1) {
                    Logger.getLogger(ServerHandler.class.getName()).log(Level.SEVERE, null, ex1);
                }
            } catch (SQLException ex){
                System.out.println("FromSQL");
            } catch (JSONException ex) {
                Logger.getLogger(ServerHandler.class.getName()).log(Level.SEVERE, null, ex);
            }
          
        }
    }
    
    public void closeConnection() throws JSONException, SQLException 
    {
        if(loggedPlayer!=null){
            System.out.println(loggedPlayer.getUsername() + " Closed connection !");
            Server.db.playerClosing(loggedPlayer);
            Server.updateplayer(loggedPlayer.getUsername(), "offline");
            JSONObject msg=new JSONObject();
            msg.put("Action", "playersignout");
            msg.put("Sender", "GM");
            msg.put("status", "offline");
            msg.put("Content",loggedPlayer.getUsername()+" left the room .");
            msg.put("username",loggedPlayer.getUsername());
            sendMsgToAll(msg);
        
        }
        
        handlers.remove(this);
        try {
            this.inputStream.close();
        } catch (IOException ex) {
            Logger.getLogger(ServerHandler.class.getName()).log(Level.SEVERE, null, ex);
        }
        this.printStream.close();
        this.stop();

    }
    
    public static void closeAllConnections() throws IOException{
        for (ServerHandler sh : handlers){
            Server.db.playerClosing(sh.loggedPlayer);
            sh.inputStream.close();
            sh.printStream.close();
            sh.stop();
        }
        handlers.clear();
    }
    public void processMessage(JSONObject msg) throws IOException, SQLException, JSONException {
        String Action = msg.getString("Action");
        switch (Action) {
            case "SignUp":
                SignUp(msg);
                break;
            case "SignIn":
                SignIn(msg);
                break;
            case "LeaderBoard":
                getTopPlayers();
                changeplayerstatus("playerBusy","busy",msg);
                break;
            case "BroadcastChat":
                broadcastMsg(msg);
                break;
            case "getallplayers":
                getAllPlayers();
                break;
            case "playerStartedMatch" :
                changeplayerstatus("playerStartedMatch","in-game",msg);
                break;
            case "playerFinishMatch":
                changeplayerstatus("playerFinishMatch","online",msg);              
                break;
            case "playerLeftWhilePlaying":
                sendMsgToReceiver(msg, msg.getString("Receiver"));
                updatePlayerScore(msg);
                closeConnection();
                break;
            case "SaveSession":
                Server.db.saveGame(msg);
                break;
            case "getSavedGames": 
                getSavedGames();
                break;
            case "ResumeMatch":
                getGameDetails(msg);
                break;
            default:
                sendMsgToReceiver(msg,msg.getString("Receiver"));
                break;
        }
    }
    public void getTopPlayers() throws SQLException{
        Vector<Person> topPlayers = Server.db.Top5Players();
        JSONArray top = new JSONArray();
        for (Person p : topPlayers) {
            JSONObject player = new JSONObject();
            player.put("name",p.getUsername());
            player.put("score",p.getTotal_score());
            top.put(player);
        }
        JSONObject msg = new JSONObject();
        msg.put("Action", "LeaderBoard");
        msg.put("TopPlayers", top);
        printStream.println(msg.toString());
    }
    public void getGameDetails(JSONObject msg) throws JSONException{
        JSONObject gameDetails = Server.db.getSavedGame(msg.getInt("gameID"));
        msg.put("gameDetails", gameDetails);
        this.printStream.println(msg.toString());
        msg.remove("Avatar");
        msg.put("Avatar", loggedPlayer.getAvatarIndex());
        this.sendMsgToReceiver(msg, msg.getString("Sender"));
        
    }

    public void changeplayerstatus(String action, String status , JSONObject msgReceived) throws JSONException, SQLException
    {
        String mode = msgReceived.getString("Mode");
        Server.updateplayer(loggedPlayer.getUsername(),status);
        if (mode.equals("Multiplayer") && action.equals("playerFinishMatch")) {
             updatePlayerScore(msgReceived);
        }
        JSONObject msg= new JSONObject();
        msg.put("Action",action);
        msg.put("status", status);
        msg.put("username",loggedPlayer.getUsername());
        sendMsgToAll(msg);
    }
    private void updatePlayerScore(JSONObject msg) throws JSONException {
        loggedPlayer.setTotal_score(msg.getInt("score"));
        loggedPlayer.setGames_won(msg.getInt("Wins"));
        loggedPlayer.setGames_lost(msg.getInt("Loses"));
        loggedPlayer.setDraws(msg.getInt("Draws"));
        loggedPlayer.setGames_played(msg.getInt("Games"));
        loggedPlayer.setStatus("online");
        Server.updateplayerScore(loggedPlayer);
    }
    public void getAllPlayers() throws JSONException
    {
       JSONArray names = new JSONArray();
       JSONArray status =new JSONArray();
       JSONObject msg = new JSONObject();
       
       for(Person p: Server.players)
       {   
           if(!p.getUsername().equals(loggedPlayer.getUsername()))
           {
                names.put(p.getUsername());
                status.put(p.getStatus());
                
           }        
       
       }
       
        msg.put("names", names);
        msg.put("status", status);
        msg.put("Action","Playerslist");
        this.printStream.println(msg.toString());msg.put("names", names);
    }
    
     public void getSavedGames() throws JSONException
    {
       JSONArray savedGames = Server.db.getPlayerSavedGames(loggedPlayer.getUsername());
       JSONObject msg = new JSONObject();
       msg.put("Action","SaveGamesList");
       msg.put("gamesArray", savedGames);
       this.printStream.println(msg.toString());
    }
    
    public void SignIn(JSONObject msg) throws SQLException, JSONException {
        int flag= Server.SignIn(msg);
        if( flag == 1)
        {  
            loggedPlayer = db.getPlayer(msg.getString("username"));
            loggedPlayer.setAvatarIndex(msg.getInt("Avatar"));
            JSONObject reply = new JSONObject();
            reply.put("Action", "playersignin");
            reply.put("Sender", "GM");
            reply.put("status", "online");
            reply.put("Content",loggedPlayer.getUsername()+" joined the room .");
            reply.put("username", loggedPlayer.getUsername());
            sendMsgToAll(reply);
        }
        sendResponse("SignIn", flag);
    }
 
    public void SignUp(JSONObject msg) throws SQLException, JSONException{
        int flag = Server.SignUp(msg);
        if (flag == 1) 
        {
            loggedPlayer = db.getPlayer(msg.getString("username"));
            loggedPlayer.setAvatarIndex(msg.getInt("Avatar"));
            JSONObject reply = new JSONObject();
            reply.put("Action", "playersignup");
            reply.put("Sender", "GM");
            reply.put("status", "online");
            reply.put("Content",loggedPlayer.getUsername()+" joined the room .");
            reply.put("username", loggedPlayer.getUsername());
            sendMsgToAll(reply);
        }
        sendResponse("SignUp", flag);

    }
    public void sendResponse(String Action, int flagDB) throws JSONException{
        JSONObject response = new JSONObject();  
        
        if(loggedPlayer!=null){
            System.out.println("From sendResponse Server handler" + loggedPlayer.getUsername());
            response.put("Action",Action);
            response.put("Response", flagDB);
            convertPlayerToJSON(loggedPlayer,response);
            
        }else{
            response.put("Action", Action);
            response.put("Response", flagDB);
        }
        printStream.println(response.toString());
    }
    
    public void convertPlayerToJSON(Person p , JSONObject json) throws JSONException{
        json.put("username", p.getUsername());
        json.put("score", p.getTotal_score());
        json.put("status", p.getStatus());
        json.put("wins", p.getGames_won());
        json.put("games", p.getGames_played());
        json.put("draws", p.getDraws());
        json.put("losses", p.getGames_lost());
    }
    // send to receiver
    // send response back to sender
    public void sendMsgToReceiver(JSONObject msg, String receiver) throws JSONException {
        
        for (ServerHandler sh : handlers) {
            if (receiver.equals(sh.loggedPlayer.getUsername())) {
                sh.printStream.println(msg.toString());
                break;
            }
        }
    }

    public void loginPlayer(String userName) throws IOException {
        for (Person p : allPlayers) {
            if (p.getUsername().equals(userName)) {
                p.setStatus("online");
                loggedPlayer = p;
                break;
            }
        }

    }

    void sendMsgToAll(JSONObject msg) 
    {
        for (ServerHandler sh : handlers) {
            if(!sh.loggedPlayer.getUsername().equals(loggedPlayer.getUsername()))
               sh.printStream.println(msg.toString());
        }
    }
    
    void broadcastMsg(JSONObject msg) 
    {
        for (ServerHandler sh : handlers) {
               sh.printStream.println(msg.toString());
        }
    }

}
