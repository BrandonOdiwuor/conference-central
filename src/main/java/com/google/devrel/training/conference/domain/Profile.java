package com.google.devrel.training.conference.domain;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.devrel.training.conference.form.ProfileForm.TeeShirtSize;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;


@Entity
public class Profile {
	String displayName;
	String mainEmail;
	TeeShirtSize teeShirtSize;
	
	@Id String userId;
	
	private List <String> conferenceKeysToAttend = new ArrayList<> (0);
	
    
    /**
     * Public constructor for Profile.
     * @param userId The user id, obtained from the email
     * @param displayName Any string user wants us to display him/her on this system.
     * @param mainEmail User's main e-mail address.
     * @param teeShirtSize The User's tee shirt size
     * 
     */
    public Profile (String userId, String displayName, String mainEmail, TeeShirtSize teeShirtSize) {
    	this.userId = userId;
    	this.displayName = displayName;
    	this.mainEmail = mainEmail;
    	this.teeShirtSize = teeShirtSize;
    }
    
	public String getDisplayName() {
		return displayName;
	}

	public String getMainEmail() {
		return mainEmail;
	}

	public TeeShirtSize getTeeShirtSize() {
		return teeShirtSize;
	}

	public String getUserId() {
		return userId;
	}
	
	public void update(String displayName, TeeShirtSize teeShirtSize){
		if(displayName != null)
			this.displayName = displayName;
		if(teeShirtSize != null)
		this.teeShirtSize = teeShirtSize;
	}
	
	/*
     * @return a list containing conferences that the user has sign up for 
     */
    public List<String> getConferenceKeysToAttend(){
        return ImmutableList.copyOf(conferenceKeysToAttend);
    }
    
    /**
     * @param conferenceKey the key to the new conference that the user is to attend
     */
    public void addToConferenceKeysToAttend(String conferenceKey){
        conferenceKeysToAttend.add(conferenceKey);
    }
    
    /*
     * Removes a conference key from conferenceKeysToAttend
     * 
     * @param conferenceKey a websafe representation of the conferenceKey
     */
    public void unregisterFromConfernce(String conferenceKey){
        if(conferenceKeysToAttend.contains(conferenceKey)){
            conferenceKeysToAttend.remove(conferenceKey);
        }
        else{
            throw new IllegalArgumentException("Invalid conferenceKey: " + conferenceKey);
        }
    }

	/**
     * Just making the default constructor private.
     */
    private Profile() {}

}
