/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.services.telephony;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Phone;

import android.net.Uri;
import android.telecom.Connection;
import android.telecom.Conference;
import android.telecom.ConferenceParticipant;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.PhoneCapabilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an IMS conference call.
 * <P>
 * An IMS conference call consists of a conference host connection and potentially a list of
 * conference participants.  The conference host connection represents the radio connection to the
 * IMS conference server.  Since it is not a connection to any one individual, it is not represented
 * in Telecom/InCall as a call.  The conference participant information is received via the host
 * connection via a conference event package.  Conference participant connections do not represent
 * actual radio connections to the participants; they act as a virtual representation of the
 * participant, keyed by a unique endpoint {@link android.net.Uri}.
 * <p>
 * The {@link ImsConference} listens for conference event package data received via the host
 * connection and is responsible for managing the conference participant connections which represent
 * the participants.
 */
public class ImsConference extends Conference {

    /**
     * TelephonyConnection class used to represent the connection to the conference server.
     */
    private class ConferenceHostConnection extends TelephonyConnection {
        protected ConferenceHostConnection(
                com.android.internal.telephony.Connection originalConnection) {
            super(originalConnection);
        }
    }

    /**
     * Listener used to respond to changes to conference participants.  At the conference level we
     * are most concerned with handling destruction of a conference participant.
     */
    private final Connection.Listener mParticipantListener = new Connection.Listener() {
        /**
         * Participant has been destroyed.  Remove it from the conference.
         *
         * @param connection The participant which was destroyed.
         */
        @Override
        public void onDestroyed(Connection connection) {
            ConferenceParticipantConnection participant =
                    (ConferenceParticipantConnection) connection;
            removeConferenceParticipant(participant);
            updateManageConference();
        }

    };

    /**
     * Listener used to respond to changes to the connection to the IMS conference server.
     */
    private final android.telecom.Connection.Listener mConferenceHostListener =
            new android.telecom.Connection.Listener() {

        /**
         * Updates the state of the conference based on the new state of the host.
         *
         * @param c The host connection.
         * @param state The new state
         */
        @Override
        public void onStateChanged(android.telecom.Connection c, int state) {
            setState(state);
        }

        /**
         * Disconnects the conference when its host connection disconnects.
         *
         * @param c The host connection.
         * @param disconnectCause The host connection disconnect cause.
         */
        @Override
        public void onDisconnected(android.telecom.Connection c, DisconnectCause disconnectCause) {
            setDisconnected(disconnectCause);
        }

        /**
         * Handles destruction of the host connection; once the host connection has been
         * destroyed, cleans up the conference participant connection.
         *
         * @param connection The host connection.
         */
        @Override
        public void onDestroyed(android.telecom.Connection connection) {
            disconnectConferenceParticipants();
        }

        /**
         * Handles changes to conference participant data as reported by the conference host
         * connection.
         *
         * @param c The connection.
         * @param participants The participant information.
         */
        @Override
        public void onConferenceParticipantsChanged(android.telecom.Connection c,
                List<ConferenceParticipant> participants) {

            if (c == null) {
                return;
            }
            Log.v(this, "onConferenceParticipantsChanged: %d participants", participants.size());
            TelephonyConnection telephonyConnection = (TelephonyConnection) c;
            handleConferenceParticipantsUpdate(telephonyConnection, participants);
        }
    };

    /**
     * The telephony connection service; used to add new participant connections to Telecom.
     */
    private TelephonyConnectionService mTelephonyConnectionService;

    /**
     * The connection to the conference server which is hosting the conference.
     */
    private TelephonyConnection mConferenceHost;

    /**
     * The known conference participant connections.  The HashMap is keyed by endpoint Uri.
     * A {@link ConcurrentHashMap} is used as there is a possibility for radio events impacting the
     * available participants to occur at the same time as an access via the connection service.
     */
    private final ConcurrentHashMap<Uri, ConferenceParticipantConnection>
            mConferenceParticipantConnections =
                    new ConcurrentHashMap<Uri, ConferenceParticipantConnection>(8, 0.9f, 1);

    /**
     * Initializes a new {@link ImsConference}.
     *
     * @param telephonyConnectionService The connection service responsible for adding new
     *                                   conferene participants.
     * @param conferenceHost The IMS radio connection hosting the conference.
     */
    public ImsConference(TelephonyConnectionService telephonyConnectionService,
            com.android.internal.telephony.Connection conferenceHost) {

        super(null);
        mTelephonyConnectionService = telephonyConnectionService;
        setConferenceHost(conferenceHost);
        setCapabilities(
                PhoneCapabilities.SUPPORT_HOLD |
                        PhoneCapabilities.HOLD |
                        PhoneCapabilities.MUTE
        );
    }

    /**
     * Not used by the IMS conference controller.
     *
     * @return {@code Null}.
     */
    @Override
    public android.telecom.Connection getPrimaryConnection() {
        return null;
    }

    /**
     * Invoked when the Conference and all its {@link Connection}s should be disconnected.
     * <p>
     * Hangs up the call via the conference host connection.  When the host connection has been
     * successfully disconnected, the {@link #mConferenceHostListener} listener receives an
     * {@code onDestroyed} event, which triggers the conference participant connections to be
     * disconnected.
     */
    @Override
    public void onDisconnect() {
        Log.v(this, "onDisconnect: hanging up conference host.");

        Call call = mConferenceHost.getCall();
        if (call != null) {
            try {
                call.hangup();
            } catch (CallStateException e) {
                Log.e(this, e, "Exception thrown trying to hangup conference");
            }
        }
    }

    /**
     * Invoked when the specified {@link android.telecom.Connection} should be separated from the
     * conference call.
     * <p>
     * IMS does not support separating connections from the conference.
     *
     * @param connection The connection to separate.
     */
    @Override
    public void onSeparate(android.telecom.Connection connection) {
        Log.wtf(this, "Cannot separate connections from an IMS conference.");
    }

    /**
     * Invoked when the specified {@link android.telecom.Connection} should be merged into the
     * conference call.
     *
     * @param connection The {@code Connection} to merge.
     */
    @Override
    public void onMerge(android.telecom.Connection connection) {
        try {
            Phone phone = ((TelephonyConnection) connection).getPhone();
            if (phone != null) {
                phone.conference();
            }
        } catch (CallStateException e) {
            Log.e(this, e, "Exception thrown trying to merge call into a conference");
        }
    }

    /**
     * Invoked when the conference should be put on hold.
     */
    @Override
    public void onHold() {
        mConferenceHost.performHold();
    }

    /**
     * Invoked when the conference should be moved from hold to active.
     */
    @Override
    public void onUnhold() {
        mConferenceHost.performUnhold();
    }

    /**
     * Invoked to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    @Override
    public void onPlayDtmfTone(char c) {
         mConferenceHost.onPlayDtmfTone(c);
    }

    /**
     * Invoked to stop playing a DTMF tone.
     */
    @Override
    public void onStopDtmfTone() {
        mConferenceHost.onStopDtmfTone();
    }

    /**
     * Handles the addition of connections to the {@link ImsConference}.  The
     * {@link ImsConferenceController} does not add connections to the conference.
     *
     * @param connection The newly added connection.
     */
    @Override
    public void onConnectionAdded(android.telecom.Connection connection) {
        // No-op
    }

    /**
     * Updates the manage conference capability of the conference.  Where there are one or more
     * conference event package participants, the conference management is permitted.  Where there
     * are no conference event package participants, conference management is not permitted.
     */
    private void updateManageConference() {
        boolean couldManageConference = PhoneCapabilities.can(getCapabilities(),
                PhoneCapabilities.MANAGE_CONFERENCE);
        boolean canManageConference = !mConferenceParticipantConnections.isEmpty();
        Log.v(this, "updateManageConference was:%s is:%s", couldManageConference ? "Y" : "N",
                canManageConference ? "Y" : "N");

        if (couldManageConference != canManageConference) {
            int newCapabilities = getCapabilities();

            if (canManageConference) {
                newCapabilities |= PhoneCapabilities.MANAGE_CONFERENCE;
            } else {
                newCapabilities = PhoneCapabilities.remove(newCapabilities,
                        PhoneCapabilities.MANAGE_CONFERENCE);
            }
            setCapabilities(newCapabilities);
        }
    }

    /**
     * Sets the connection hosting the conference and registers for callbacks.
     *
     * @param conferenceHost The connection hosting the conference.
     */
    private void setConferenceHost(com.android.internal.telephony.Connection conferenceHost) {
        if (Log.VERBOSE) {
            Log.v(this, "setConferenceHost " + conferenceHost);
        }

        mConferenceHost = new ConferenceHostConnection(conferenceHost);
        mConferenceHost.addConnectionListener(mConferenceHostListener);
    }

    /**
     * Handles state changes for conference participant(s).  The participants data passed in
     *
     * @param parent The connection which was notified of the conference participant.
     * @param participants The conference participant information.
     */
    private void handleConferenceParticipantsUpdate(
            TelephonyConnection parent, List<ConferenceParticipant> participants) {

        boolean newParticipantsAdded = false;
        boolean oldParticipantsRemoved = false;
        ArrayList<ConferenceParticipant> newParticipants = new ArrayList<>(participants.size());
        HashSet<Uri> participantEndpoints = new HashSet<>(participants.size());

        // Add any new participants and update existing.
        for (ConferenceParticipant participant : participants) {
            Uri endpoint = participant.getEndpoint();
            participantEndpoints.add(endpoint);
            if (!mConferenceParticipantConnections.containsKey(endpoint)) {
                createConferenceParticipantConnection(parent, participant);
                newParticipants.add(participant);
                newParticipantsAdded = true;
            } else {
                ConferenceParticipantConnection connection =
                        mConferenceParticipantConnections.get(endpoint);
                connection.updateState(participant.getState());
            }
        }

        // Set state of new participants.
        if (newParticipantsAdded) {
            // Set the state of the new participants at once and add to the conference
            for (ConferenceParticipant newParticipant : newParticipants) {
                ConferenceParticipantConnection connection =
                        mConferenceParticipantConnections.get(newParticipant.getEndpoint());
                connection.updateState(newParticipant.getState());
            }
        }

        // Finally, remove any participants from the conference that no longer exist in the
        // conference event package data.
        Iterator<Map.Entry<Uri, ConferenceParticipantConnection>> entryIterator =
                mConferenceParticipantConnections.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<Uri, ConferenceParticipantConnection> entry = entryIterator.next();

            if (!participantEndpoints.contains(entry.getKey())) {
                ConferenceParticipantConnection participant = entry.getValue();
                participant.removeConnectionListener(mParticipantListener);
                removeConnection(participant);
                entryIterator.remove();
                oldParticipantsRemoved = true;
            }
        }

        // If new participants were added or old ones were removed, we need to ensure the state of
        // the manage conference capability is updated.
        if (newParticipantsAdded || oldParticipantsRemoved) {
            updateManageConference();
        }
    }

    /**
     * Creates a new {@link ConferenceParticipantConnection} to represent a
     * {@link ConferenceParticipant}.
     * <p>
     * The new connection is added to the conference controller and connection service.
     *
     * @param parent The connection which was notified of the participant change (e.g. the
     *                         parent connection).
     * @param participant The conference participant information.
     */
    private void createConferenceParticipantConnection(
            TelephonyConnection parent, ConferenceParticipant participant) {

        // Create and add the new connection in holding state so that it does not become the
        // active call.
        ConferenceParticipantConnection connection = new ConferenceParticipantConnection(
                parent.getOriginalConnection(), participant);
        connection.addConnectionListener(mParticipantListener);

        if (Log.VERBOSE) {
            Log.v(this, "createConferenceParticipantConnection: %s", connection);
        }

        mConferenceParticipantConnections.put(participant.getEndpoint(), connection);
        PhoneAccountHandle phoneAccountHandle =
                TelecomAccountRegistry.makePstnPhoneAccountHandle(parent.getPhone());
        mTelephonyConnectionService.addExistingConnection(phoneAccountHandle, connection);
        addConnection(connection);
    }

    /**
     * Removes a conference participant from the conference.
     *
     * @param participant The participant to remove.
     */
    private void removeConferenceParticipant(ConferenceParticipantConnection participant) {
        if (Log.VERBOSE) {
            Log.v(this, "removeConferenceParticipant: %s", participant);
        }

        participant.removeConnectionListener(mParticipantListener);
        participant.getEndpoint();
        mConferenceParticipantConnections.remove(participant.getEndpoint());
    }

    /**
     * Disconnects all conference participants from the conference.
     */
    private void disconnectConferenceParticipants() {
        Log.v(this, "disconnectConferenceParticipants");

        for (ConferenceParticipantConnection connection :
                mConferenceParticipantConnections.values()) {

            removeConferenceParticipant(connection);

            // Mark disconnect cause as cancelled to ensure that the call is not logged in the
            // call log.
            connection.setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
            connection.destroy();
        }
        mConferenceParticipantConnections.clear();
    }

    /**
     * Changes the state of the Ims conference.
     *
     * @param state the new state.
     */
    public void setState(int state) {
        Log.v(this, "setState %s", Connection.stateToString(state));

        switch (state) {
            case Connection.STATE_INITIALIZING:
            case Connection.STATE_NEW:
            case Connection.STATE_RINGING:
            case Connection.STATE_DIALING:
                // No-op -- not applicable.
                break;
            case Connection.STATE_DISCONNECTED:
                setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                        mConferenceHost.getOriginalConnection().getDisconnectCause()));
                destroy();
                break;
            case Connection.STATE_ACTIVE:
                setActive();
                break;
            case Connection.STATE_HOLDING:
                setOnHold();
                break;
        }
    }

    /**
     * Builds a string representation of the {@link ImsConference}.
     *
     * @return String representing the conference.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ImsConference objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" state:");
        sb.append(Connection.stateToString(getState()));
        sb.append(" hostConnection:");
        sb.append(mConferenceHost);
        sb.append(" participants:");
        sb.append(mConferenceParticipantConnections.size());
        sb.append("]");
        return sb.toString();
    }
}
