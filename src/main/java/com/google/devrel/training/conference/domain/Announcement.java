package com.google.devrel.training.conference.domain;

/**
 * A Simple wrapper for message
 */

public class Announcement {
    
    private String message;
    
    public Announcement(String message){
        this.message = message;
    }
    
    public String getMessage(){
        return message;
    }
}
