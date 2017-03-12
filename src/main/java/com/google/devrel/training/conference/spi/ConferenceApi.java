package com.google.devrel.training.conference.spi;

import static com.google.devrel.training.conference.service.OfyService.ofy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.ApiMethod.HttpMethod;
import com.google.api.server.spi.config.Named;
import com.google.api.server.spi.response.ConflictException;
import com.google.api.server.spi.response.ForbiddenException;
import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.users.User;
import com.google.devrel.training.conference.Constants;
import com.google.devrel.training.conference.domain.Announcement;
import com.google.devrel.training.conference.domain.Conference;
import com.google.devrel.training.conference.domain.Profile;
import com.google.devrel.training.conference.form.ConferenceForm;
import com.google.devrel.training.conference.form.ConferenceQueryForm;
import com.google.devrel.training.conference.form.ProfileForm;
import com.google.devrel.training.conference.form.ProfileForm.TeeShirtSize;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.cmd.Query;

/**
 * Defines conference APIs.
 */
@Api(name = "conference", 
	version = "v1", 
	scopes = { Constants.EMAIL_SCOPE }, 
	clientIds = {
        Constants.WEB_CLIENT_ID, 
        Constants.API_EXPLORER_CLIENT_ID }, 
	description = "API for the Conference Central Backend application.")
public class ConferenceApi {

    /*
     * Get the display name from the user's email. For example, if the email is
     * lemoncake@example.com, then the display name becomes "lemoncake."
     */
    private static String extractDefaultDisplayNameFromEmail(String email) {
        return email == null ? null : email.substring(0, email.indexOf("@"));
    }

    /**
     * Creates or updates a Profile object associated with the given user
     * object.
     *
     * @param user
     *            A User object injected by the cloud endpoints.
     * @param profileForm
     *            A ProfileForm object sent from the client form.
     * @return Profile object just created.
     * @throws UnauthorizedException
     *             when the User object is null.
     */

    // Declare this method as a method available externally through Endpoints
    @ApiMethod(name = "saveProfile", path = "profile", httpMethod = HttpMethod.POST)    
    public Profile saveProfile(final User user, ProfileForm profileForm) throws UnauthorizedException {
    	
    	// If a user is not logged in throw UnauthorizedException
    	if(user == null)
        	throw new UnauthorizedException("Authorization recquired");
    	
    	// get userId and mainEmail
        String userId = user.getUserId();        
        String mainEmail = user.getEmail();   
        
        // get displayName and teeShirtSize sent by the request
        String displayName = profileForm.getDisplayName();        
        TeeShirtSize teeShirtSize = profileForm.getTeeShirtSize();
        
        Profile profile = getProfile(user);   
        
        if(profile == null){
        	// Extract displayName from email if not specified by the request
        	if(displayName == null)
        		displayName = extractDefaultDisplayNameFromEmail(mainEmail);
        	
        	// set teeShirtSize to NOT_SPECIFIED if not specified by the request
            if(teeShirtSize == null)
            	teeShirtSize = TeeShirtSize.NOT_SPECIFIED;
        	// Create a new profile
        	profile = new Profile(userId, displayName, mainEmail, teeShirtSize);
        }
        else{
        	// Update existing profile
        	profile.update(displayName, teeShirtSize);
        }
        
        // Saving a profile to the database
        ofy().save().entity(profile).now();

        // Return the profile
        return profile;
    }

    /**
     * Returns a Profile object associated with the given user object. The cloud
     * endpoints system automatically inject the User object.
     *
     * @param user
     *            A User object injected by the cloud endpoints.
     * @return Profile object.
     * @throws UnauthorizedException
     *             when the User object is null.
     */
    @ApiMethod(name = "getProfile", path = "profile", httpMethod = HttpMethod.GET)
    public Profile getProfile(final User user) throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        // load the Profile Entity
        String userId = user.getUserId();
        Key key = Key.create(Profile.class, userId);
        Profile profile = (Profile) ofy().load().key(key).now(); // TODO load the Profile entity
        return profile;
    }
    
    /**
     * Creates a new Conference object and stores it to the datastore.
     *
     * @param user A user who invokes this method, null when the user is not signed in.
     * @param conferenceForm A ConferenceForm object representing user's inputs.
     * @return A newly created Conference Object.
     * @throws UnauthorizedException when the user is not signed in.
     */
    @ApiMethod(name = "createConference", path = "conference", httpMethod = HttpMethod.POST)
    public Conference createConference(final User user, final ConferenceForm conferenceForm)
        throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        // Get the userId of the logged in User
        final String userId = user.getUserId();

        // Get the key for the User's Profile
        final Key<Profile> profileKey = Key.create(Profile.class, userId);

        // Allocate a key for the conference -- let App Engine allocate the ID
        // Don't forget to include the parent Profile in the allocated ID
        final Key<Conference> conferenceKey = ObjectifyService.factory().allocateId(profileKey, Conference.class);

        // Get the Conference Id from the Key
        final long conferenceId = conferenceKey.getId();//        
        
        // Get default queue
        final Queue queue = QueueFactory.getDefaultQueue();
        
        Conference conference = ofy().transact(new Work<Conference>(){
            @Override
            public Conference run(){
                // Get the existing Profile entity for the current user if there is one
                Profile profile = ofy().load().key(profileKey).now();
                
                // Create a new profile if the user has no profile
                if(profile == null){
                    profile = new Profile(userId, extractDefaultDisplayNameFromEmail(user.getEmail()), user.getEmail(), TeeShirtSize.NOT_SPECIFIED);
                }

                // Create a new Conference Entity, specifying the user's Profile entity
                // as the parent of the conference
                Conference conference = new Conference(conferenceId, userId, conferenceForm);

                // Save Conference and Profile Entities
                ofy().save().entities(conference, profile).now();
                 
                // 
                queue.add(ofy().getTransaction(), TaskOptions.Builder.withUrl("/task/send_confirmation_email")
                        .param("email", profile.getMainEmail())
                        .param("conferenceInfo", conference.toString()));

                 return conference;
            }
        });
        
        return conference;        
    }
    
    /**
     * Queries against the datastore with given filters and returns the result
     * 
     * @return A list of conferences that match the given filter
     */
    @ApiMethod(name = "queryConferences", path = "queryConferences", httpMethod = HttpMethod.POST)
    public List<Conference> queryConference(ConferenceQueryForm conferenceQueryForm){
    	//Query<Conference> query = ofy().load().type(Conference.class).order("name");
    	
    	return conferenceQueryForm.getQuery().list();
    }
    
    /**
     * Returns a list of conferences created by the user
     * 
     * @return list of conferences created by the user ordered by name
     */
    @ApiMethod(name = "getConferencesCreated", path = "getConferencesCreated", httpMethod = HttpMethod.POST)
    public List<Conference> getConferencesCreated(User user) throws UnauthorizedException{
    	// If a user is not logged in throw UnauthorizedException
    	if(user == null)
        	throw new UnauthorizedException("Authorization recquired");
    	
    	// Get the key for the User
        Key<Profile> profileKey = Key.create(Profile.class, user.getUserId());
    	
    	Query<Conference> query = ofy().load().type(Conference.class).ancestor(profileKey).order("name");
    	
    	return query.list();
    }
    
    /**
     * 
     */
    public List<Conference> filterPlayground(){
    	Query query = ofy().load().type(Conference.class);
    	query = query.filter("city = ", "London");
    	query = query.filter("topics = ", "Health");
    	query = query.filter("month =", 6);
    	query = query.filter("maxAttendees >", 0).order("maxAttendees").order("name");
    	return query.list();
    }
    
    /**
     * Just a wrapper for Boolean.
     * We need this wrapped Boolean because endpoints functions must return
     * an object instance, they can't return a Type class such as
     * String or Integer or Boolean
     */
    public static class WrappedBoolean {

        private final Boolean result;
        private final String reason;

        public WrappedBoolean(Boolean result) {
            this.result = result;
            this.reason = "";
        }

        public WrappedBoolean(Boolean result, String reason) {
            this.result = result;
            this.reason = reason;
        }

        public Boolean getResult() {
            return result;
        }

        public String getReason() {
            return reason;
        }
    }
    
    /**
     * Register to attend the specified Conference.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeConferenceKey The String representation of the Conference Key.
     * @return Boolean true when success, otherwise false
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "registerForConference",
            path = "conference/{websafeConferenceKey}/registration",
            httpMethod = HttpMethod.POST
    )    
    public WrappedBoolean registerForConference(final User user,
            @Named("websafeConferenceKey") final String websafeConferenceKey)
            throws UnauthorizedException, NotFoundException,
            ForbiddenException, ConflictException {
     // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        // Get the userId
        final String userId = user.getUserId();

        // Start transaction
        WrappedBoolean result = ofy().transact(new Work<WrappedBoolean>() {
            @Override
            public WrappedBoolean run(){
                try {

                // Get the conference key
                // throws ForbiddenException if the key cannot be created
                Key<Conference> conferenceKey = Key.create(websafeConferenceKey);

                // Get the Conference entity from the datastore
                Conference conference = ofy().load().key(conferenceKey).now();

                // 404 when there is no Conference with the given conferenceId.
                if (conference == null) {
                    return new WrappedBoolean (false,
                            "No Conference found with key: "
                                    + websafeConferenceKey);
                }

                // Get the user's Profile entity
                Profile profile = getProfile(user);

                // Has the user already registered to attend this conference?
                if (profile.getConferenceKeysToAttend().contains(
                        websafeConferenceKey)) {
                    return new WrappedBoolean (false, "Already registered");
                } else if (conference.getSeatsAvailable() <= 0) {
                    return new WrappedBoolean (false, "No seats available");
                } else {
                    // Add the websafeConferenceKey to the profile's
                    // conferencesToAttend property
                    profile.addToConferenceKeysToAttend(websafeConferenceKey);
                    
                    // Decrease the conference's seatsAvailable
                    conference.bookSeats(1);
                    
                    // Save the Conference and Profile entities
                    ofy().save().entities(profile, conference).now();
                    
                    // We are booked!
                    return new WrappedBoolean(true, "Registration successful");
                }

                }
                catch (Exception e) {
                    return new WrappedBoolean(false, "Unknown exception");
                }
            }
        });
        // if result is false
        if (!result.getResult()) {
            if (result.getReason().contains("No Conference found with key")) {
                throw new NotFoundException (result.getReason());
            }
            else if (result.getReason() == "Already registered") {
                throw new ConflictException("You have already registered");
            }
            else if (result.getReason() == "No seats available") {
                throw new ConflictException("There are no seats available");
            }
            else {
                throw new ForbiddenException("Unknown exception");
            }
        }
        return result;
    }
    
    /**
     * Returns a Conference object with the given conferenceId.
     *
     * @param websafeConferenceKey The String representation of the Conference Key.
     * @return a Conference object with the given conferenceId.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "getConference",
            path = "conference/{websafeConferenceKey}",
            httpMethod = HttpMethod.GET
    )
    public Conference getConference(
            @Named("websafeConferenceKey") final String websafeConferenceKey)
            throws NotFoundException {
        Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        Conference conference = ofy().load().key(conferenceKey).now();
        if (conference == null) {
            throw new NotFoundException("No Conference found with key: " + websafeConferenceKey);
        }
        return conference;
    }
    
    /**
     * Returns a collection of Conference Object that the user is going to attend.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @return a Collection of Conferences that the user is going to attend.
     * @throws UnauthorizedException when the User object is null.
     */
    @ApiMethod(
            name = "getConferencesToAttend",
            path = "getConferencesToAttend",
            httpMethod = HttpMethod.GET
    )
    public Collection<Conference> getConferencesToAttend(final User user)
            throws UnauthorizedException, NotFoundException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        
        // Get the Profile entity for the user
        Profile profile = getProfile(user); // Change this;
        if (profile == null) {
            throw new NotFoundException("Profile doesn't exist.");
        }
        
        // Get the value of the profile's conferenceKeysToAttend property
        List<String> keyStringsToAttend = profile.getConferenceKeysToAttend(); // change this

        // Collection of conferences that the user is to attend
        Collection<Conference> conferencesToAttend = new ArrayList<Conference>(0);
        for(String key : keyStringsToAttend){
            conferencesToAttend.add(getConference(key));
        }

        return conferencesToAttend;  // change this
    }
    
    /**
     * Unregister from the specified Conference.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeConferenceKey The String representation of the Conference Key to unregister
     *                             from.
     * @return Boolean true when success, otherwise false.
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "unregisterFromConference",
            path = "conference/{websafeConferenceKey}/registration",
            httpMethod = HttpMethod.DELETE
    )
    public WrappedBoolean unregisterFromConference(final User user,
                                            @Named("websafeConferenceKey")
                                            final String websafeConferenceKey)
            throws UnauthorizedException, NotFoundException, ForbiddenException, ConflictException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        WrappedBoolean result = ofy().transact(new Work<WrappedBoolean>() {
            @Override
            public WrappedBoolean run() {
                Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
                Conference conference = ofy().load().key(conferenceKey).now();
                // 404 when there is no Conference with the given conferenceId.
                if (conference == null) {
                    return new  WrappedBoolean(false,
                            "No Conference found with key: " + websafeConferenceKey);
                }

                // Un-registering from the Conference.
                Profile profile = (Profile) ofy().load().key(Key.create(Profile.class, user.getUserId())).now();                
                if (profile.getConferenceKeysToAttend().contains(websafeConferenceKey)) {
                    profile.unregisterFromConfernce(websafeConferenceKey);;
                    conference.giveBackSeats(1);
                    ofy().save().entities(profile, conference).now();
                    return new WrappedBoolean(true);
                } else {
                    return new WrappedBoolean(false, "You are not registered for this conference");
                }
            }
        });
        // if result is false
        if (!result.getResult()) {
            if (result.getReason().contains("No Conference found with key")) {
                throw new NotFoundException (result.getReason());
            }
            else {
                throw new ForbiddenException(result.getReason());
            }
        }
        // NotFoundException is actually thrown here.
        return new WrappedBoolean(result.getResult());
    }
    
    @ApiMethod(
            name = "getAnnouncement",
            path = "announcement",
            httpMethod = HttpMethod.GET
    )
    public Announcement getAnnouncement() {
        MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
        Object message = memcacheService.get(Constants.MEMCACHE_ANNOUNCEMENTS_KEY);
        if (message != null) {
            return new Announcement(message.toString());
        }
        return null;
    }
    
}
