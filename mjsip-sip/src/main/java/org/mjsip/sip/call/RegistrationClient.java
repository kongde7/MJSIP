/*
 * Copyright (C) 2005 Luca Veltri - University of Parma - Italy
 * 
 * This source code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 * Author(s):
 * Luca Veltri (luca.veltri@unipr.it)
 */

package org.mjsip.sip.call;



import java.util.Vector;
import java.util.concurrent.ScheduledFuture;

import org.mjsip.sip.address.GenericURI;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipNameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.authentication.DigestAuthentication;
import org.mjsip.sip.header.AuthorizationHeader;
import org.mjsip.sip.header.CSeqHeader;
import org.mjsip.sip.header.ContactHeader;
import org.mjsip.sip.header.ExpiresHeader;
import org.mjsip.sip.header.Header;
import org.mjsip.sip.header.ProxyAuthenticateHeader;
import org.mjsip.sip.header.ProxyAuthorizationHeader;
import org.mjsip.sip.header.StatusLine;
import org.mjsip.sip.header.ViaHeader;
import org.mjsip.sip.header.WwwAuthenticateHeader;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipMethods;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.transaction.TransactionClient;
import org.mjsip.sip.transaction.TransactionClientListener;
import org.slf4j.LoggerFactory;



/** RegistrationClient does register (one time or periodically)
  * a contact address with a registrar server.
  */
public class RegistrationClient implements TransactionClientListener {
	
	private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RegistrationClient.class);

	/** RegistrationClient listener */
	protected final RegistrationClientListener _listener;
		
	/** SipProvider */
	protected final SipProvider _sipProvider;

	/** URI of the registrar server */
	protected final SipURI _registrarUri;

	/** Target AOR to be registered with the registrar server (the URI in the To header field) */
	protected final NameAddress _toNAddr;

	/** URI of the user that is actually performing the registration (the URI in the From header field) */
	protected final NameAddress _fromNAddr;

	/** User's contact name address. */
	protected final NameAddress _contactNAddr;

	/** User name. */
	protected final String _username;

	/** User name. */
	protected final String _realm;

	/** User's passwd. */
	protected final String _passwd;

	/** Nonce for the next authentication. */
	protected String _nextNonce=null;

	/** Qop for the next authentication. */
	protected String _qop=null;

	/** Expiration time. */
	protected int _expireTime;

	/** Renew time. */
	protected int _renewTime;

	long _attemptTimeout;

	/** Whether keep on registering. */
	boolean _loop=false;

	/** Whether the thread is running. */
	boolean _running=false;

	/** Number of registration attempts. */
	int _attempts=0;
	
	/** SipKeepAlive daemon. */
	//SipKeepAlive keep_alive=null;

		
	/** Attempt timeout */
	ScheduledFuture<?> _attemptTimer;

	/** Registration timeout */
	ScheduledFuture<?> _registrationTimer;

	/**
	 * Creates a new RegistrationClient with authentication credentials (i.e. username, realm, and
	 * passwd).
	 * <p>
	 * The From URI is equal to the To URI. The Contact URI is automatically formed with the user
	 * name from the AOR and the address:port from the SIP provider. If a secure transport is preset
	 * (e.g. TLS), a SIPS URI is registered as contact URI.
	 * 
	 * @param sip_provider
	 *        the SIP provider
	 * @param registrar
	 *        the registrar server
	 * @param to_naddr
	 *        the AOR of the resource that has to be registered (the URI in the To header field)
	 * @param username
	 *        the username for authentication
	 * @param realm
	 *        the realm for authentication
	 * @param passwd
	 *        the password for authentication
	 * @param listener
	 *        the RegistrationClient listener
	 */
	public RegistrationClient(SipProvider sip_provider, SipURI registrar, NameAddress to_naddr, String username, String realm, String passwd, RegistrationClientListener listener) {
		this(sip_provider, registrar, to_naddr, to_naddr, null, listener, username, realm, passwd);
	}

	/**
	 * Creates a {@link RegistrationClient}.
	 *
	 * @param sip_provider
	 *        the SIP provider
	 * @param regConfig
	 *        The configuration options for registration.
	 * @param listener
	 *        the RegistrationClient listener
	 */
	public RegistrationClient(SipProvider sip_provider, RegistrationOptions regConfig,
			RegistrationClientListener listener) {
		this(sip_provider, new SipURI(regConfig.getRegistrar()), regConfig.getUserURI(), regConfig.getAuthUser(),
				regConfig.getAuthRealm(), regConfig.getAuthPasswd(), listener);
	}

	/**
	 * Inits the RegistrationClient.
	 * 
	 * @param sip_provider
	 *        the SIP provider
	 * @param registrar_uri
	 *        the registrar server
	 * @param to_naddr
	 *        the AOR of the resource that has to be registered (the URI in the To header field)
	 * @param from_naddr
	 *        the URI of the registering UA (the URI in the From header field)
	 * @param contact_naddr
	 *        the registered contact URI
	 * @param listener
	 *        the RegistrationClient listener
	 * @param username
	 *        the username for authentication
	 * @param realm
	 *        the realm for authentication
	 * @param passwd
	 *        the password for authentication
	 */
	public RegistrationClient(SipProvider sip_provider, SipURI registrar_uri, NameAddress to_naddr,
			NameAddress from_naddr, NameAddress contact_naddr, RegistrationClientListener listener, String username, String realm, String passwd) {
		_listener = listener;
		_sipProvider = sip_provider;
		_registrarUri = registrar_uri;
		if (contact_naddr==null) {
			GenericURI to_uri=to_naddr.getAddress();
			String user=(to_uri.isSipURI())? new SipURI(to_uri).getUserName() : null;
			contact_naddr=new NameAddress(sip_provider.getContactAddress(user));
		}
		if (SipNameAddress.isSIPS(contact_naddr)) {
			// change scheme of to-uri, from-uri, and request-uri to SIPS
			to_naddr=SipNameAddress.toSIPS(to_naddr);
			from_naddr=SipNameAddress.toSIPS(from_naddr);
			registrar_uri.setSecure(true);
		}
		_toNAddr = to_naddr;
		_fromNAddr = from_naddr;
		_contactNAddr = contact_naddr;

		_expireTime = sip_provider.sipConfig().getDefaultExpires();
		_renewTime = sip_provider.sipConfig().getDefaultExpires();

		_username = username;
		_realm = realm;
		_passwd = passwd;
	}


	/** Gets the target AOR registered with the registrar server.
	  * @return the AOR */
	public NameAddress getTargetAOR() {
		return _toNAddr;
	}


	/** Whether it is periodically registering. */
	public boolean isRegistering() {
		return _running;
	}


	/** Registers with the registrar server.
	  * It does register with the previously set expire time.  */
	public void register() {
		register(_expireTime);
	}


	/** Unregister with the registrar server.
	  * It does register with expire time = 0. */
	public void unregister() {
		register(0);
	} 


	/** Registers with the registrar server for <i>expire_time</i> seconds. */
	public void register(int expire_time) {
		register(expire_time,null,null);
	}


	/** Registers with the registrar server for <i>expire_time</i> seconds, with a given message body. */
	protected void register(int expire_time, String content_type, byte[] body) {
		_attempts=0;
		if (expire_time>0) this._expireTime=expire_time;
		String call_id=_sipProvider.pickCallId();
		SipMessage req=_sipProvider.messageFactory().createRegisterRequest(_registrarUri,_toNAddr,_fromNAddr,_contactNAddr,call_id);
		req.setExpiresHeader(new ExpiresHeader(String.valueOf(expire_time)));
		if (_nextNonce!=null) {
			AuthorizationHeader ah=new AuthorizationHeader("Digest");
			//GenericURI to_uri=to_naddr.getAddress();
			ah.addUsernameParam(_username);
			ah.addRealmParam(_realm);
			ah.addNonceParam(_nextNonce);
			ah.addUriParam(req.getRequestLine().getAddress().toString());
			ah.addQopParam(_qop);
			String response=(new DigestAuthentication(SipMethods.REGISTER,ah,null,_passwd)).getResponse();
			ah.addResponseParam(response);
			req.setAuthorizationHeader(ah);
		}
		if (body!=null) {
			LOG.debug("Register body type: " + content_type + "; length: " + body.length + " bytes");
			req.setBody(content_type,body);
		}
		if (expire_time > 0) {
			LOG.info("Registering contact: " + _contactNAddr + " (expiry " + expire_time + " secs)");
		} else {
			LOG.info("Unregistering contact: " + _contactNAddr);
		}
		TransactionClient t=new TransactionClient(_sipProvider,req,this);
		t.request(); 
	}


	/** Unregisters all contacts with the registrar server.
	  * It performs an unregistration (registration with 0 secs as expiration time) using '*' as contact address. */
	public void unregisterall() {
		_attempts=0;
		NameAddress user=new NameAddress(_toNAddr);
		String call_id=_sipProvider.pickCallId();
		SipMessage req=_sipProvider.messageFactory().createRegisterRequest(_registrarUri,_toNAddr,_fromNAddr,(NameAddress)null,call_id);
		//ContactHeader contact_star=new ContactHeader(); // contact is *
		//req.setContactHeader(contact_star);
		req.setExpiresHeader(new ExpiresHeader(String.valueOf(0)));
		LOG.info("unregistering all contacts");
		TransactionClient t=new TransactionClient(_sipProvider,req,this); 
		t.request(); 
	}


	/** Periodically registers with the registrar server.
	  * @param expire_time expiration time in seconds
	  * @param renew_time renew time in seconds */
	public void loopRegister(int expire_time, int renew_time) {
		this._expireTime=expire_time;
		this._renewTime=renew_time;
		cancelAttemptTimeout();
		cancelRegistrationTimeout();
		_loop=true;
		register(expire_time);
	}

	private void cancelAttemptTimeout() {
		if (_attemptTimer != null)
			_attemptTimer.cancel(false);
		_attemptTimer = null;
	}

	private void cancelRegistrationTimeout() {
		if (_registrationTimer != null)
			_registrationTimer.cancel(false);
		_registrationTimer = null;
	}

	/** Periodically registers with the registrar server.
	  * @param expire_time expiration time in seconds
	  * @param renew_time renew time in seconds
	  * @param keepalive_time keep-alive packet rate (inter-arrival time) in milliseconds */
	/*public void loopRegister(int expire_time, int renew_time, long keepalive_time) {
		loopRegister(expire_time,renew_time);
		// keep-alive
		if (keepalive_time>0) {
			SipURI to_uri=to_naddr.getAddress();
			String host=to_uri.getHost();
			int port=to_uri.getPort();
			if (port<0) port=SipStack.default_port;
			new SipKeepAlive(sip_provider,new SocketAddress(host,port),null,keepalive_time);
		}
	}*/


	/** Halts the periodic registration. */
	public void halt() {
		if (_running) _loop=false;
		//if (keep_alive!=null) keep_alive.halt();
	}

	
	// **************** Transaction callback functions *****************

	/** Callback function called when client sends back a provisional response. */
	@Override
	public void onTransProvisionalResponse(TransactionClient transaction, SipMessage resp) {
		// do nothing..
	}

	/** Callback function called when client sends back a success response. */
	@Override
	public void onTransSuccessResponse(TransactionClient transaction, SipMessage resp) {
		if (transaction.getTransactionMethod().equals(SipMethods.REGISTER)) {
			if (resp.hasAuthenticationInfoHeader()) {
				_nextNonce=resp.getAuthenticationInfoHeader().getNextnonceParam();
			}
			StatusLine status=resp.getStatusLine();
			String result=status.getCode()+" "+status.getReason();
			
			// update the renew_time
			int expires=0;
			if (resp.hasExpiresHeader()) {
				expires=resp.getExpiresHeader().getDeltaSeconds();
			} else if (resp.hasContactHeader()) {
				Vector<Header> contacts = resp.getContacts().getHeaders();
				for (int i=0; i<contacts.size(); i++) {
					int exp_i = (new ContactHeader(contacts.elementAt(i))).getExpires();
					if (exp_i>0 && (expires==0 || exp_i<expires)) expires=exp_i;
				}    
			}
			if (expires>0 && expires<_renewTime) _renewTime=expires;
			
			LOG.info("Registration " + result + ", expires in " + expires + "s, renewing in " + _renewTime + "s.");
			if (_loop) {
				cancelAttemptTimeout();
				_registrationTimer = _sipProvider.scheduler().schedule((long) _renewTime * 1000,
						this::onRegistrationTimeout);
				LOG.trace("Scheduling next registration in " + _renewTime + "s");
			}
			if (_listener != null)
				_listener.onRegistrationSuccess(this, _toNAddr, _contactNAddr, expires, result);
		}
	}

	/** Callback function called when client sends back a failure response. */
	@Override
	public void onTransFailureResponse(TransactionClient transaction, SipMessage resp) {
		if (transaction.getTransactionMethod().equals(SipMethods.REGISTER)) {
			StatusLine status=resp.getStatusLine();
			int code=status.getCode();
			if (code==401 && _attempts<_sipProvider.sipConfig().getRegAuthAttempts() && resp.hasWwwAuthenticateHeader() && resp.getWwwAuthenticateHeader().getRealmParam().equalsIgnoreCase(_realm)) {
				// UAS authentication
				_attempts++;
				SipMessage req=transaction.getRequestMessage();
				CSeqHeader csh=req.getCSeqHeader().incSequenceNumber();
				req.setCSeqHeader(csh);
				ViaHeader vh=req.getViaHeader();
				req.removeViaHeader();
				vh.setBranch(SipProvider.pickBranch());
				req.addViaHeader(vh);
				WwwAuthenticateHeader wah=resp.getWwwAuthenticateHeader();
				String qop_options=wah.getQopOptionsParam();
				//LOG.debug("qop-options: "+qop_options);
				_qop=(qop_options!=null)? "auth" : null;
				AuthorizationHeader ah=(new DigestAuthentication(SipMethods.REGISTER,req.getRequestLine().getAddress().toString(),wah,_qop,null,0,null,_username,_passwd)).getAuthorizationHeader();
				req.setAuthorizationHeader(ah);
				TransactionClient t=new TransactionClient(_sipProvider,req,this);
				t.request();
			}
			else
			if (code==407 && _attempts<_sipProvider.sipConfig().getRegAuthAttempts() && resp.hasProxyAuthenticateHeader() && resp.getProxyAuthenticateHeader().getRealmParam().equalsIgnoreCase(_realm)) {
				// Proxy authentication
				_attempts++;
				SipMessage req=transaction.getRequestMessage();
				req.setCSeqHeader(req.getCSeqHeader().incSequenceNumber());
				ProxyAuthenticateHeader pah=resp.getProxyAuthenticateHeader();
				String qop_options=pah.getQopOptionsParam();
				//LOG.debug("qop-options: "+qop_options);
				_qop=(qop_options!=null)? "auth" : null;
				ProxyAuthorizationHeader ah=(new DigestAuthentication(SipMethods.REGISTER,req.getRequestLine().getAddress().toString(),pah,_qop,null,0,null,_username,_passwd)).getProxyAuthorizationHeader();
				req.setProxyAuthorizationHeader(ah);
				TransactionClient t=new TransactionClient(_sipProvider,req,this);
				t.request();
			}
			else {
				// Registration failure
				String result=code+" "+status.getReason();
				LOG.info("Registration failure: "+result);
				if (_loop) {
					cancelRegistrationTimeout();
					_attemptTimeout = _sipProvider.sipConfig().getRegMaxAttemptTimeout();
					_attemptTimer = _sipProvider.scheduler().schedule(_attemptTimeout, this::onAttemptTimeout);
					LOG.trace("next attempt after "+(_sipProvider.sipConfig().getRegMaxAttemptTimeout()/1000)+" secs");
				}
				if (_listener != null)
					_listener.onRegistrationFailure(this, _toNAddr, _contactNAddr, result);
			}
		}
	}

	/** Callback function called when client expires timeout. */
	@Override
	public void onTransTimeout(TransactionClient transaction) {
		if (transaction.getTransactionMethod().equals(SipMethods.REGISTER)) {
			LOG.info("Registration failure: No response from server");
			if (_loop) {
				cancelRegistrationTimeout();
				long inter_time_msecs = (_attemptTimer == null) ? _sipProvider.sipConfig().getRegMinAttemptTimeout()
						: _attemptTimeout * 2;
				if (inter_time_msecs>_sipProvider.sipConfig().getRegMaxAttemptTimeout()) inter_time_msecs=_sipProvider.sipConfig().getRegMaxAttemptTimeout();
				_attemptTimeout = inter_time_msecs;
				_attemptTimer = _sipProvider.scheduler().schedule(_attemptTimeout, this::onAttemptTimeout);
				LOG.trace("next attempt after "+(inter_time_msecs/1000)+" secs");
			}
			if (_listener != null)
				_listener.onRegistrationFailure(this, _toNAddr, _contactNAddr, "Timeout");
		}
	}


	// ******************* Timer callback functions ********************

	private void onAttemptTimeout() {
		if (_loop) {
			register();
		}
		_attemptTimer = null;
	}

	private void onRegistrationTimeout() {
		if (_loop) {
			register();
		}
		_registrationTimer = null;
	}

	// ***************************** run() *****************************

	/** Run method */
	public void run() {
		
		_running=true;
		try {
			while (_loop) {
				register();
				Thread.sleep(_renewTime*1000);
			}
		}
		catch (Exception e) {
			LOG.info("Exception.", e);
		}
		_running=false;
	}

	
}
