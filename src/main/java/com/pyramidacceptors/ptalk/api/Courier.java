/*
 * Copyright (C) 2014 Pyramid Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.pyramidacceptors.ptalk.api;

import com.pyramidacceptors.ptalk.api.event.*;
import jssc.SerialPortException;
import jssc.SerialPortTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Courier runs the communication loop. This only applies to serial protocols<br>
 * that use a polling logic. e.g. ccTalk or RS-232<br>
 * Courier is threadsafe.
 * <br>
 *
 * @author <a href="mailto:cory@pyramidacceptors.com">Cory Todd</a>
 * @since 1.0.0.0
 */
final class Courier extends Thread {
    private final Logger logger = LoggerFactory.getLogger(Courier.class);
    // Socket to handle all data IO with slave
    private final RS232Socket socket;
    // EventListner list - threadsafe
    private final CopyOnWriteArrayList<RS232EventListener> listeners;
    private PyramidPort port;
    private AtomicBoolean _isPaused = new AtomicBoolean(false);
    private AtomicBoolean _isStopped = new AtomicBoolean(true);
    private AtomicBoolean _stopThread = new AtomicBoolean(false);
    private AtomicBoolean _resetRequested = new AtomicBoolean(false);
    private AtomicBoolean _serialNumberRequested = new AtomicBoolean(false);
    private boolean _comOkay = true;
    private int _failureCount = 0;
    // Used in case the customer is not using a Pyramid validator.
    private byte rawAcceptorModel;
    private byte rawFirmwareRevision;
    private String rawSerialNumber;

    private CreditActions creditAction = CreditActions.NONE;

    /**
     * Create a new Courier instance<br>
     * <br>
     *
     * @param port   to deliver on and listen to
     * @param socket type of packet that will be handled
     */
    Courier(PyramidPort port, RS232Socket socket) {
        this.port = port;
        this.listeners = new CopyOnWriteArrayList<>();
        this.socket = socket;
    }

    /**
     * Sleep for d milliseconds<br>
     * <br>
     *
     * @param d sleep time
     */
    private static void sleep(int d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException ex) {
            LoggerFactory.getLogger("CourierSleeper")
                    .error("Sleep interrupted: {}", ex);
        }
    }

    /**
     * @return true if the comms are operating properly. The flag may be<br>
     * set to false under the following conditions:
     * SerialPort disconnected: Device unreachable
     * Unit stop responding to polls - this is common in RS-232 during <br>
     * validation. Consider debouncing this value. The logs
     * will report timing out during validation. This is normal.
     */
    boolean getCommsOkay() {
        return this._comOkay;
    }

    /**
     * Returns the firmware revision of the connected acceptor.
     *
     * @return String
     */
    public String getFirmwareRevision() {
        return String.format("1.%02x", rawFirmwareRevision & 0xff);
    }

    /**
     * Returns the  acceptor model of the connected acceptor
     *
     * @return AcceptorModel
     */
    public String getAcceptorModel() {
        return AcceptorModel.fromByte(rawAcceptorModel);
    }

    /**
     * Returns the serial number of the attached acceptor
     *
     * @return String
     */
    public String getSerialNumber() {
        return this.rawSerialNumber;
    }

    /**
     * Inject a reset request into the next message loop
     */
    public void requestReset() {
        _resetRequested.set(true);
    }

    /**
     * Inject a serial number request in the next message loop
     */
    public void requestSerialNumer() {
        _serialNumberRequested.set(true);
    }

    /**
     * Enable or disable the message loop. This effectively stops communication
     * without closing the port. If pause is enabled for more than 8 seconds,
     * the bill acceptor will disable itself because the host is not responding.
     *
     * @param pause
     */
    public void pause(boolean pause) {
        _isPaused.set(pause);
    }

    /**
     * Subscribe to events generated by this instance<br>
     * <br>
     *
     * @param l PyramidSerialEventListener
     */
    public void addChangeListener(RS232EventListener l) {
        this.listeners.add(l);
    }

    /**
     * Remove all subscriptions to this event.<br>
     * <br>
     */
    public void removeChangeListeners() {
        this.listeners.clear();
    }

    /**
     * Remove subscription to events generate by this instance<br>
     * <br>
     *
     * @param l PyramidSerialEventListener
     */
    public void removeChangeListener(RS232EventListener l) {
        this.listeners.remove(l);
    }

    // Event firing method.  Called internally by other class methods.
    private void fireChangeEvent(PTalkEvent e) {
        for (RS232EventListener l : listeners) {
            l.changeEventReceived(e);
        }
    }

    /**
     * Stop the execution and null out reference objects
     */
    protected void stopThread() {
        this._stopThread.set(true);
        logger.debug("Stopping courier thread...");
        while (this._isStopped.get()) {
            sleep(5);
        }
        logger.debug("Courier thread stopped");
    }

    /**
     * Start the courier thread. Poll in intervals determined by the poll<br>
     * rate passed to this instance's constructor.
     */
    @Override
    public void run() {

        // Loop until we receive client calls the stop thread method

        _isStopped.set(false);
        while (!_stopThread.get()) {

            try {

                // Just burn time in POLL_RATE increments while paused
                while (_isPaused.get()) {

                    sleep(RS232Configuration.INSTANCE.getPollrate());
                }

                if (_serialNumberRequested.get()) {

                    handleSerialNumberRequest();

                } else if (_resetRequested.get()) {

                    handleResetRequest();

                } else {

                    handleNormalLoop();
                }

                // If we made it this far, the loop has not stalled.
                _comOkay = true;

            } catch (SerialPortException ex) {
                logger.error(ex.getMessage());
                _comOkay = false;
            } catch (SerialPortTimeoutException ex1) {
                logger.error("SendBytes timed out. ({} ms)", APIConstants.COMM_TIMEOUT);
                _comOkay = false;
            }


            // Detect if there have been a number of sequential communication failures. Notify as needed.
            if (!_comOkay) {
                if (_failureCount++ >= RS232Configuration.INSTANCE.getPollRetryLimit()) {
                    fireChangeEvent(new ConnectionFailureEvent(this, _failureCount));
                }
            } else {
                _failureCount = 0;
            }


            // Wait for pollRate milliseconds before looping through again
            sleep(RS232Configuration.INSTANCE.getPollrate());
        }

        _isStopped.set(true);
    }

    private void handleNormalLoop() throws SerialPortException, SerialPortTimeoutException {

        byte[] resp = writeWrapper(socket.generateCommand(creditAction));

        handleEvents(resp);
    }

    private void handleResetRequest() throws SerialPortException, SerialPortTimeoutException {


        logger.debug("Reset request command being generated");

        // Clear flag before we touch the serial port in case the slave does not
        // support the request
        _resetRequested.set(false);

        // No response is expected
        writeWrapper(socket.generateCommandCustom(RS232Packet.resetBytes()));

        // We should delay after reset. 500 ms should do it
        port.closePort();

        sleep(500);
        port.openPort();
        logger.debug("Acceptor reset performed");
    }

    private void handleSerialNumberRequest() throws SerialPortException, SerialPortTimeoutException {

        logger.debug("Serial number command being generated");

        // Clear flag before we touch the serial port in case the slave does not
        // support the request
        _serialNumberRequested.set(false);

        byte[] command = socket.generateCommandCustom(RS232Packet.serialNumberBytes());

        byte[] resp = writeWrapper(command);

        byte[] sn = new byte[5];

        if(resp.length < (sn.length + 3)) {
            logger.warn("Invalid serial number response::%s", Utilities.bytesToString(resp));
            return;
        }

        System.arraycopy(resp, 3, sn, 0, sn.length);

        // Since the serial number is 9 digits in length, we encode as 4 BCD values
        // plus a single byte on the end. Remove the second to last zero to fix this.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i == 4)
                sb.append(Integer.toHexString(sn[i]));
            else
                sb.append(Utilities.leftPadding(Integer.toHexString(sn[i]), 2, '0'));
        }

        rawSerialNumber = sb.toString();
        logger.debug("Serial number response parsed");
    }

    /**
     * Writes specified data to port and performs minor housekeeping tasks
     *
     * @param command byte[] to send to slave
     * @return byte[] response
     * @throws SerialPortException        if the is a hardware fault
     * @throws SerialPortTimeoutException if the slave does not respond in a timely manner
     */
    private byte[] writeWrapper(byte[] command) throws SerialPortException, SerialPortTimeoutException {

        // Notify client that we're sending a packet
        fireChangeEvent(SerialDataEvent.newTxEvent(this, Utilities.bytesToString(command)));

        port.write(command);

        // Collect the response
        byte[] resp = port.readBytes(socket.getMaxPacketRespSize());

        // Notify that we've received a response
        fireChangeEvent(SerialDataEvent.newRxEvent(this, Utilities.bytesToString(resp)));

        if (!RS232Packet.isValid(resp)) {
            port.flush();
            logger.debug("Invalid data received, flushed port and ignoring data::%s", Utilities.bytesToString(resp));
            resp = new byte[0];
        }

        return resp;

    }

    private void handleEvents(byte[] resp) {

        if(resp == null || resp.length == 0) {
            return;
        }

        RS232Packet respPacket = socket.parseResponse(resp);

        creditAction = respPacket.getCreditAction();
        rawFirmwareRevision = respPacket.getFirmwareRevision();
        rawAcceptorModel = respPacket.getAcceptorModel();

        PTalkEvent e;
        for (Events type : respPacket.getInterpretedEvents()) {

            // Is this an event that requires extra data?
            switch (type) {

                case Credit:
                    e = new CreditEvent(
                            this,
                            Utilities.bytesToString(resp),
                            respPacket.getBillName());
                    break;

                case Escrowed:
                    e = new EscrowedEvent(
                            this,
                            Utilities.bytesToString(resp),
                            respPacket.getBillName());
                    break;

                default:
                    e = new PTalkEvent(this, type, respPacket.getByteString());

            }

            // Notify any listeners that we have data
            fireChangeEvent(e);
        }
    }
}
