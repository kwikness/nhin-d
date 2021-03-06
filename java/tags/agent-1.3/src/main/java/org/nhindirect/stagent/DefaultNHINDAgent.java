/* 
Copyright (c) 2010, NHIN Direct Project
All rights reserved.

Authors:
   Umesh Madan     umeshma@microsoft.com
   Greg Meyer      gm2552@cerner.com
 
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
in the documentation and/or other materials provided with the distribution.  Neither the name of the The NHIN Direct Project (nhindirect.org). 
nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS 
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE 
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.nhindirect.stagent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.cms.CMSSignedData;
import org.nhindirect.stagent.annotation.AgentDomains;
import org.nhindirect.stagent.annotation.PrivateCerts;
import org.nhindirect.stagent.annotation.PublicCerts;
import org.nhindirect.stagent.cert.CertificateResolver;
import org.nhindirect.stagent.cert.X509CertificateEx;
import org.nhindirect.stagent.cryptography.Cryptographer;
import org.nhindirect.stagent.cryptography.SMIMECryptographerImpl;
import org.nhindirect.stagent.cryptography.SMIMEStandard;
import org.nhindirect.stagent.cryptography.SignedEntity;
import org.nhindirect.stagent.mail.Message;
import org.nhindirect.stagent.mail.MimeEntity;
import org.nhindirect.stagent.mail.MimeError;
import org.nhindirect.stagent.mail.MimeException;
import org.nhindirect.stagent.mail.MimeStandard;
import org.nhindirect.stagent.mail.WrappedMessage;
import org.nhindirect.stagent.parser.EntitySerializer;
import org.nhindirect.stagent.trust.TrustAnchorResolver;
import org.nhindirect.stagent.trust.TrustEnforcementStatus;
import org.nhindirect.stagent.trust.TrustError;
import org.nhindirect.stagent.trust.TrustException;
import org.nhindirect.stagent.trust.TrustModel;

import com.google.inject.Inject;

/**
 * Default agent implementation.  Implements {@l MutableAgent} to support updating agent properties at runtime.
 * @author Greg Meyer
 * @author Umesh Madan
 * @since 1.0
 */
public class DefaultNHINDAgent implements NHINDAgent, MutableAgent
{	
	private static final Log LOGGER = LogFactory.getFactory().getInstance(DefaultNHINDAgent.class);
	
	private static boolean initialConstruct = true;
	
	static MimeMultipart lastMMPart = null;

    protected final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
	
	private Cryptographer cryptographer;
    private CertificateResolver privateCertResolver;
    private Collection<CertificateResolver> publicCertResolver;
    private TrustAnchorResolver trustAnchors;

    private TrustModel trustModel;
    private TrustEnforcementStatus minTrustRequirement;
    private Collection<String> domains;
    private NHINDAgentEventListener m_listener = null;
    
    private boolean encryptionEnabled = true;
    private boolean wrappingEnabled = true;
    
    static
    {
		Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }
    
    /**
     * Constructs an agent with a domain, certificate stores, and a trust anchor store.
     * @param domain The domain that this agent will be serving.
     * @param internalCerts A certificate store for messages originating internally.  The store contains certificates that have access to private keys for decryption and 
     * signing messages.
     * @param externalCerts A certificate store for incoming messages.  The store contains public certificates for message signature validation and encryption. 
     * @param trustSettings A certificate store for certificate anchors.  Certificate anchors are certificates that can validate the authenticity of
     * a certificate.  They are also used by the agent to determine if a certificate is trusted by the system.
     */
    public DefaultNHINDAgent(String domain, CertificateResolver privateCerts, CertificateResolver publicCerts, TrustAnchorResolver anchors)
    {
    	
    	this(domain, privateCerts, publicCerts, anchors, TrustModel.Default, SMIMECryptographerImpl.Default);
    }

    /**
     * Constructs an agent with a list of domains, certificate stores, and a trust anchor store.
     * @param domain A list of domains that this agent will be serving.
     * @param internalCerts A certificate store for messages originating internally.  The store contains certificates that have access to private keys for decryption and 
     * signing messages.
     * @param externalCerts A certificate store for incoming messages.  The store contains public certificates for message signature validation and encryption. 
     * @param trustSettings A certificate store for certificate anchors.  Certificate anchors are certificates that can validate the authenticity of
     * a certificate.  They are also used by the agent to determine if a certificate is trusted by the system.
     */    
    public DefaultNHINDAgent(Collection<String> domains, CertificateResolver privateCerts, CertificateResolver publicCerts, TrustAnchorResolver anchors)
    {

    	
    	this(domains, privateCerts, publicCerts, anchors, TrustModel.Default, SMIMECryptographerImpl.Default);
    }    
    

    
    /**
     * Constructs an agent with domain, certificate services, and trust anchor store.
     * @param domain The domain that this agent will be serving.
     * @param internalCerts A certificate store for messages originating internally.  The store contains certificates that have access to private keys for decryption and 
     * signing messages.
     * @param externalCerts A certificate store for incoming messages.  The store contains public certificates for message signature validation and encyprtion. 
     * @param trustSettings A certificate store for certificate anchors.  Certificate anchors are certificates that can validate the authenticity of
     * a certificate.  They are also used by the agent to determine if a certificate is trusted by the system.
     * @param A trust model implementation that asserts the if a message is trusted.
     * @param A cryptography implementation used to sign, encrypt, and decrypt messages.
     */    
    public DefaultNHINDAgent(String domain, CertificateResolver privateCerts, CertificateResolver publicCerts, TrustAnchorResolver anchors, TrustModel trustModel, Cryptographer cryptographer)
    {            	
    	this(Arrays.asList(domain), privateCerts, Arrays.asList(publicCerts), anchors, trustModel, cryptographer);    	
    }

    /**
     * Constructs an agent with a list of domains, certificate stores, and a trust anchor store.
     */   
    @Inject
    public DefaultNHINDAgent(@AgentDomains Collection<String> domains, @PrivateCerts CertificateResolver privateCerts, 
    		@PublicCerts Collection<CertificateResolver> publicCerts, TrustAnchorResolver anchors, 
    		TrustModel trustModel, Cryptographer cryptographer)
    {

    	if (domains == null || domains.size() == 0 || privateCerts == null || publicCerts == null || anchors == null || trustModel == null || cryptographer == null)
        {
            throw new IllegalArgumentException();
        }

    	if (initialConstruct)
    	{
	    	StringBuilder domainLogInfo = new StringBuilder("Initializing NHINDAgent\r\nLocal domains:");
	    	for (String domain : domains)
	    		domainLogInfo.append("\r\n\t" + domain);
	    			
	    	LOGGER.info(domainLogInfo);
	    	initialConstruct = false;
    	}
    	
    	
    	
        this.domains = domains;
        this.privateCertResolver = privateCerts;
        this.publicCertResolver = publicCerts;
        this.cryptographer = cryptographer;
        this.trustAnchors = anchors;
        this.trustModel = trustModel;
        this.minTrustRequirement = TrustEnforcementStatus.Success_Offline;
        
        if (this.trustModel.getCertChainValidator() != null && 
        		!this.trustModel.getCertChainValidator().isCertificateResolver())
        {
        	this.trustModel.getCertChainValidator().setCertificateResolver(this.publicCertResolver);
        }
    }          
    
    /**
     * Constructs an agent with a list of domain, certificate services, and trust anchor store.
     * @param domain A list of domains that this agent will be serving.
     * @param internalCerts A certificate store for messages originating internally.  The store contains certificates that have access to private keys for decryption and 
     * signing messages.
     * @param externalCerts A certificate store for incoming messages.  The store contains public certificates for message signature validation and encyprtion. 
     * @param trustSettings A certificate store for certificate anchors.  Certificate anchors are certificates that can validate the authenticity of
     * a certificate.  They are also used by the agent to determine if a certificate is trusted by the system.
     * @param A trust model implementation that asserts the if a message is trusted.
     * @param A cryptography implementation used to sign, encrypt, and decrypt messages.
     */   
    
    public DefaultNHINDAgent( Collection<String> domains,  CertificateResolver privateCerts, 
    		 CertificateResolver publicCerts, TrustAnchorResolver anchors, TrustModel trustModel, Cryptographer cryptographer)
    {            	    	
    	this(domains, privateCerts, Arrays.asList(publicCerts), anchors, trustModel, cryptographer);  
    }    
    
    /**
     * {@inheritDoc}
     */
	public void setDomains(Collection<String> domains)
	{
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{
    		this.domains = domains;
    	}
    	finally
    	{
    		lock.unlock();
    	}
	}
    
    
    /**
     * {@inheritDoc}
     */
    public Collection<String> getDomains()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{
    		return Collections.unmodifiableCollection(domains);
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }

    
    /**
     * {@inheritDoc}
     */
    public Cryptographer getCryptographer()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{
    		return this.cryptographer;
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }

    /**
     * {@inheritDoc}
     */
	public void setCryptographer(Cryptographer cryptographer)
	{
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{
    		this.cryptographer = cryptographer;
    	}
    	finally
    	{
    		lock.unlock();
    	}
	}
    
    /**
     * Indicates if messages are required to be encrypted in the agent.
     * @return True if messages are required to be encrypted in the agent.  False otherwise.
     */
    public boolean isEncryptMessages()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{
    		return this.encryptionEnabled;
    	}
    	finally
    	{
    		lock.unlock();
    	}
    } 
    
    /**
     * Sets if messages are required to be encrypted in the agen
     * @param value True if messages are required to be encrypted in the agent.  False otherwise.
     */
    public void setEncryptMessages(boolean value)
    {
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{    	
    		this.encryptionEnabled = value;
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }

    /**
     * {@inheritDoc}
     */
    public boolean isWrappingEnabled() 
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{       	
    		return wrappingEnabled;
    	}
    	finally
    	{
    		lock.unlock();
    	}
    		
	}

    /**
     * {@inheritDoc}
     */
	public void setWrappingEnabled(boolean wrappingEnabled) 
	{
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{   
    		this.wrappingEnabled = wrappingEnabled;
    	}
    	finally
    	{
    		lock.unlock();
    	}
	}


	/**
     * Gets the certificate store used to encrypt messages and validate signatures.  This store generally contains only public certificates
     * @return The certificate store used to encrypt messages and validate signatures.
     * @deprecated Use {{@link #getPublicCertResolvers()}
     */
    public CertificateResolver getPublicCertResolver()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{   
	    	if (publicCertResolver != null && publicCertResolver.size() > 0)
	    		return this.publicCertResolver.iterator().next();
	    	
	    	return null;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }

    /**
     * {@inheritDoc}
     */
    public Collection<CertificateResolver> getPublicCertResolvers()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{   
    		return this.publicCertResolver;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }    
    
    /**
     * {@inheritDoc}
     */
	public void setPublicCertResolvers(Collection<CertificateResolver> resolvers)
	{
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{   
    		this.publicCertResolver = resolvers;
    	}
    	finally
    	{
    		lock.unlock();
    	}
	}
    
    /**
     * {@inheritDoc}
     */
    public CertificateResolver getPrivateCertResolver()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{     	
    		return this.privateCertResolver;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }

    /**
     * {@inheritDoc}
     */
    public void setPrivateCertResolver(CertificateResolver resolver)
    {
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{ 
    		this.privateCertResolver = resolver;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }
    
    /**
     * {@inheritDoc}
     */
    public TrustAnchorResolver getTrustAnchors()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{        
    		return this.trustAnchors;
    	}
    	finally
    	{
    		lock.unlock();
    	}		
    }

    /**
     * {@inheritDoc}
     */
    public void setTrustAnchorResolver(TrustAnchorResolver resolver)
    {
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{ 
    		this.trustAnchors = resolver;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }
    
    /**
     * Gets the minimum trust status applied to messages by the agent.
     * @return The minimum trust status applied to messages by the agent.
     */
    public TrustEnforcementStatus getMinTrustRequirement()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{     	
    		return this.minTrustRequirement;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }
        
    /**
     * Sets the minimum trust status applied to messages by the agent.
     * @param value The minimum trust status applied to messages by the agent.
     */
    public void setMinTrustRequirement(TrustEnforcementStatus value)
    {
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{ 
	        if (value.compareTo(TrustEnforcementStatus.Success_Offline) < 0)
	        {
	            throw new IllegalArgumentException();
	        }
	        this.minTrustRequirement = value;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }

    /**
     * {@inheritDoc}
     */
    public void setEventListener(NHINDAgentEventListener listener)
    {
    	Lock lock = readWriteLock.writeLock();
    	lock.lock();
    	try
    	{     	
    		m_listener = listener;
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }
    
    
    /**
     * {@inheritDoc}
     */
    public NHINDAgentEventListener getEventListener()
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{ 
    		return m_listener;
      	}
    	finally
    	{
    		lock.unlock();
    	}		
    }
    

	/**
	 * Processes an incoming message represented by a raw string.  The message will be decrypted and validated that it meets trust assertions.
	 * @param messageText The raw contents of the incoming message that will be processed.
	 * @return An incoming messaging object that contains the unwrapped and decrypted message. 
	 */    
    public IncomingMessage processIncoming(String messageText)
    {
        if (messageText == null || messageText.length() == 0)
        {
            throw new IllegalArgumentException();
        }

        return processIncoming(new IncomingMessage(messageText));
    }    
    
	/**
	 * Processes an incoming message represented by a raw string.  The message will be decrypted and validated that it meets trust assertions.
	 * @param messageText The raw contents of the incoming message that will be processed.
	 * @param recipients The recipients of the message.  This overrides the routing headers in the message.
	 * @param sender The sender of the message.  This overrides the to FROM routing header in the message.
	 * @return An incoming messaging object that contains the unwrapped and decrypted message. 
	 */       
    public IncomingMessage processIncoming(String messageText, NHINDAddressCollection recipients, NHINDAddress sender)
    {
        this.checkEnvelopeAddresses(recipients, sender);
        
        IncomingMessage message = new IncomingMessage(messageText, recipients, sender);
        return this.processIncoming(message);                    
    }    
    
    
	/**
	 * Processes a pre-enveloped message.  The message will be decrypted and validated that it meets trust assertions.
	 * @param envelope A message envelope containing the incoming message.
	 * @return An incoming messaging object that contains the unwrapped and decrypted message. 
	 */      
    public IncomingMessage processIncoming(MessageEnvelope envelope)
    {
        if (envelope == null)
        {
            throw new IllegalArgumentException();
        }
        
        this.checkEnvelopeAddresses(envelope);
        return this.processIncoming(new IncomingMessage(envelope));
    }    
    
    
	/**
	 * Processes an incoming mime message.  The message will be decrypted and validated that it meets trust assertions.
	 * @param msg The incoming mime message. 
	 * @return An incoming messaging object that contains the unwrapped and decrypted message. 
	 */ 
    public IncomingMessage processIncoming(MimeMessage msg)
    {
    	if (msg == null)
    	{
    		throw new IllegalArgumentException();
    	}
    	IncomingMessage inMsg = null;
    	try
    	{
    		inMsg = new IncomingMessage(new Message(msg));
    		
    	}
    	catch (MessagingException e)
    	{
    		throw new MimeException(MimeError.InvalidMimeEntity, e);
    	}
    	
    	return processIncoming(inMsg);
    }    
    

    
	/**
	 * Processes a pre-enveloped message.  The message will be decrypted and validated that it meets trust assertions.
	 * @param envelope A message envelope containing the incoming message.
	 * @return An incoming messaging object that contains the unwrapped and decrypted message. 
	 */  
    public IncomingMessage processIncoming(IncomingMessage message)
    {          
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{ 
	    	if (message == null)
	        {
	            throw new IllegalArgumentException();
	        }    	
	
	    	if (LOGGER.isDebugEnabled())
	    		LOGGER.debug("Processing incoming message:\r\n" + message.toString() + "\r\n");    	
	    	    	
	        try
	        {
	            message.setAgent(this);
	            message.validate();
	            
	            if (m_listener != null)
	            	m_listener.preProcessIncoming(message);            
	
	            processMessage(message);
	
	            if (m_listener != null)
	            	m_listener.postProcessIncoming(message);                
	            
	        	if (LOGGER.isDebugEnabled())
	        		LOGGER.debug("Completed processing incoming message.  Result message:\r\n" + EntitySerializer.Default.serialize(message.getMessage()) + "\r\n");              
	            
	            return message;
	        }
	        catch (Exception error)
	        {        	
	        	LOGGER.error("Error processing incoming message: " + error.getMessage(), error);        	
	        	
	        	NHINDException throwError = new NHINDException(error);
	        	
	            if (m_listener != null)
	            	m_listener.errorIncoming(message, error);  
	            throw throwError;  // rethrow error
	        }
    	}
    	finally
    	{
    		lock.unlock();
    	}	
    }

    /*
     * Process the incoming message by apply the security and trust algorithms. 
     *
     */
    protected void processMessage(IncomingMessage message)
    {    	
    	///CLOVER:OFF
        if (message.getSender() == null)
        {
            throw new TrustException(TrustError.UntrustedSender);
        }
        ///CLOVER:ON

        message.categorizeRecipients(this.getDomains());
        if (!message.hasDomainRecipients())
        {
        	throw new AgentException(AgentError.NoTrustedRecipients);
        }
        //
        // Map each address to its certificates/trust settings
        //
        this.bindAddresses(message);
        //
        // Extract signed content from the message
        //
        this.decryptSignedContent(message);
        
        //
        // The standard requires that the original message be wrapped to protect headers
        //
        message.setMessage(this.unwrapMessage(message.getMessage()));

        // Enforce trust requirements, including checking signatures
        //
        this.trustModel.enforce(message);        
        
        //
        // Remove any untrusted recipients...
        //
        if (message.hasDomainRecipients())
        {
            message.categorizeRecipients(this.minTrustRequirement);
        }
        if (!message.hasDomainRecipients())
        {
            throw new TrustException(TrustError.NoTrustedRecipients);
        }        
        
        message.updateRoutingHeaders();        
    }

    /*
     * Binds the addresses with certificates and trust anchors
     */
    protected void bindAddresses(IncomingMessage message)
    {

   		message.getSender().setCertificates(this.resolvePublicCerts(message.getSender(), false));
    	
        //
        // Bind each recpient's certs and trust settings
        //

        for (NHINDAddress recipient : message.getDomainRecipients())
        {
        	Collection<X509Certificate> privateCerts = this.resolvePrivateCerts(recipient, false);
        	if (privateCerts == null || privateCerts.size() == 0)
        		LOGGER.warn("bindAddresses(IncomingMessage message) - Could not resolve a private certificate for recipient " + recipient.getAddress());
        		
            recipient.setCertificates(privateCerts);
            
            Collection<X509Certificate> anchors = this.trustAnchors.getIncomingAnchors().getCertificates(recipient);
            if (anchors == null || anchors.size() == 0)
            	LOGGER.warn("bindAddresses(IncomingMessage message) - Could not obtain incoming trust anchors for recipient " + recipient.getAddress());
            recipient.setTrustAnchors(anchors);
        }
    }

    /*
     * Decrypts the signed message
     */
    @SuppressWarnings("unchecked")
    protected void decryptSignedContent(IncomingMessage message)
    {
        
        MimeEntity decryptedEntity = this.decryptMessage(message);
        CMSSignedData signatures;
        MimeEntity payload;
        try
        {
	        if (SMIMEStandard.isContentEnvelopedSignature(new ContentType(decryptedEntity.getContentType())))
	        {
	            signatures = cryptographer.deserializeEnvelopedSignature(decryptedEntity);                
	            payload = new MimeEntity(new ByteArrayInputStream(signatures.getContentInfo().getEncoded()));
	        }                        
	        else if (SMIMEStandard.isContentMultipartSignature(new ContentType(decryptedEntity.getContentType())))
	        {
	            //
	            // Extract the signature envelope. That contains both the signature and the actual message content
	            //
				ByteArrayDataSource dataSource = new ByteArrayDataSource(decryptedEntity.getRawInputStream(), decryptedEntity.getContentType());
				
				MimeMultipart verifyMM = new MimeMultipart(dataSource);	    	                 	
	        	
	            SignedEntity signedEntity = SignedEntity.load(verifyMM);                  
	            signatures = cryptographer.deserializeSignatureEnvelope(signedEntity);
	            payload = signedEntity.getContent(); 
	        }
	        else
	        {
	            throw new AgentException(AgentError.UnsignedMessage);
	        }        
	        
	        message.setSignature(signatures);
	        
	        //
	        // Alter body to contain actual content. Also clean up mime headers on the message that were there to support
	        // signatures etc
	        //         	
        	InternetHeaders headers = new InternetHeaders();
        	
        	// remove all mime headers
        	Enumeration<Header> eHeaders = message.getMessage().getAllHeaders();
        	while (eHeaders.hasMoreElements())
        	{
        		Header hdr = (Header)eHeaders.nextElement();
        		if (!MimeStandard.startsWith(hdr.getName(), MimeStandard.HeaderPrefix))
        			headers.setHeader(hdr.getName(), hdr.getValue());
        	}
        	
        	
        	// add back in headers from original message
        	eHeaders = payload.getAllHeaders();
        	while (eHeaders.hasMoreElements())
        	{
        		Header hdr = (Header)eHeaders.nextElement();
        		headers.setHeader(hdr.getName(), hdr.getValue());
        	}   
			
        	Message msg = new Message(headers, payload.getContentAsBytes());
        	message.setMessage(msg);
        }
        catch (MessagingException e)
        {
        	throw new MimeException(MimeError.InvalidBody, e);
        }
        catch (IOException e)
        {
        	throw new MimeException(MimeError.InvalidBody, e);
        }        
    }

    /*
     * Decrypts the message content.
     */
    protected MimeEntity decryptMessage(IncomingMessage message)
    {
        MimeEntity decryptedEntity = null;
        if (this.encryptionEnabled)
        {
            //
            // Yes, this can be optimized heavily for multiple certs. 
            // But we will start with the easy to understand simple version
            //            
            // Decrypt and parse message body into a signature entity - the envelope that contains our data + signature
            // We can use the cert of any ONE of the recipients to decrypts
            // So basically, we'll try until we find one, or we just run out...
            //
            for (X509Certificate cert : message.getDomainRecipients().getCertificates())
            {
                try
                {
                	if (cert instanceof X509CertificateEx)
                	{
                		X509CertificateEx privCert = (X509CertificateEx)cert;
                		decryptedEntity = this.cryptographer.decrypt(message.getMessage(), privCert);
                		break;
                	}
                }
                catch (Exception e)
                {
                }
            }
        }
        else
        {
        	try
        	{
        		decryptedEntity = new MimeEntity(message.getMessage().getRawInputStream());
        	}
        	catch (MessagingException ex)
        	{
        		throw new AgentException(AgentError.MissingMessage);
        	}
        }
        
        if (decryptedEntity == null)
        {
            throw new AgentException(AgentError.UntrustedMessage);
        }

        return decryptedEntity;
    }    
    
	/**
	 * Processes an outgoing message represented by a raw string.  The message will be wrapped, encrypted, and signed.
	 * @param messageText The raw contents of the incoming message that will be processed.
	 * @return An outoing messaging object that contains the wrapped message that is and encrypted and signed. 
	 */      
    public OutgoingMessage processOutgoing(String messageText)
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{ 
	    	if (messageText == null || messageText.length() == 0)
	    		throw new IllegalArgumentException();
	    	
	        OutgoingMessage message = new OutgoingMessage(this.wrapMessage(messageText));
	        
	        return this.processOutgoing(message);
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }
    
	/**
	 * Processes an outgoing message represented by a raw string.  The message will be wrapped, encrypted, and signed.
	 * @param messageText The raw contents of the incoming message that will be processed.
	 * @param recipients The recipients of the message.  This overrides the routing headers in the message.
	 * @param sender The sender of the message.  This overrides the to FROM routing header in the message.
	 * @return An outoing messaging object that contains the wrapped message that is and encrypted and signed. 
	 */        
    public OutgoingMessage processOutgoing(String messageText, NHINDAddressCollection recipients, NHINDAddress sender)
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{ 
	        this.checkEnvelopeAddresses(recipients, sender);
	
	        OutgoingMessage message = new OutgoingMessage(this.wrapMessage(messageText), recipients, sender);            
	        return this.processOutgoing(message);  
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }    
    
	/**
	 * Processes an outgoing pre-enveloped message.  The message will be wrapped, encrypted, and signed.
	 * @param envelope A message envelope containing the outgoing message.
	 * @return An outoing messaging object that contains the wrapped message that is and encrypted and signed. 
	 */    
    public OutgoingMessage processOutgoing(MessageEnvelope envelope)
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{ 
	        if (envelope == null)
	            throw new IllegalArgumentException();
	        
	        this.checkEnvelopeAddresses(envelope);
	
	        OutgoingMessage message = new OutgoingMessage(envelope);
	        return this.processOutgoing(message);
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }    
    
	/**
	 * Processes an outgoing pre-enveloped message.  The message will be wrapped, encrypted, and signed.
	 * @param message A message envelope containing the incoming message.
	 * @return An outoing messaging object that contains the wrapped message that is and encrypted and signed. 
	 */ 
    public OutgoingMessage processOutgoing(OutgoingMessage message)
    {
    	Lock lock = readWriteLock.readLock();
    	lock.lock();
    	try
    	{ 
	        if (message == null)
	            throw new IllegalArgumentException();
	     
	    	//LOGGER.debug("Processing outgoing message:\r\n" + message.getMessage().g + "\r\n");    	        
	        
	        message.setAgent(this);    
	                
	        message.validate();
	
	        try
	        {
	
	            if (m_listener != null)
	            	m_listener.preProcessOutgoing(message);                
	
	            processMessage(message);
	
	            if (m_listener != null)
	            	m_listener.postProcessOutgoing(message);      
	            
	        	//if (LOGGER.isDebugEnabled())
	        	//	LOGGER.debug("Completed processing outing message.  Result message:\r\n" + EntitySerializer.Default.serialize(message) + "\r\n");             
	        }
	        catch (Exception error)
	        {        	        	        
	        	LOGGER.error("Error processing outgoing message: " + error.getMessage(), error);
	        	
	        	NHINDException throwError = new NHINDException(error);
	        	
	            if (m_listener != null)
	            	m_listener.errorOutgoing(message, error);  
	            throw throwError;  // rethrow error
	        }
	        
	        return message;
    	}
    	finally
    	{
    		lock.unlock();
    	}
    }

	/*
	 * Processes an outgoing message.  The message will be singed, encrypted, and validated that it meets trust assertions.
	 * 
	 */    
    protected void processMessage(OutgoingMessage message)
    {
	    if (!WrappedMessage.isWrapped(message.getMessage()))
	    {
	        message.setMessage(this.wrapMessage(message.getMessage()));
	    }
    	
    	///CLOVER:OFF
        if (message.getSender() == null)
        {
            throw new AgentException(AgentError.MissingFrom);
        }
        ///CLOVER:ON

        this.bindAddresses(message);
        
        if (!message.hasRecipients())
        {
            throw new AgentException(AgentError.MissingTo);
        }
        

        message.categorizeRecipients(this.getDomains());
        
        //
        // Enforce the trust model.
        //
        this.trustModel.enforce(message);        
        
        
        message.categorizeRecipients(this.minTrustRequirement);
        if (!message.hasRecipients())
        {
            throw new AgentException(AgentError.NoTrustedRecipients);
        }        

        //
        // Finally, sign and encrypt the message
        //
        this.signAndEncryptMessage(message);
        
        //
        // Not all recipients may be trusted. Remove them from Routing headers
        //
        message.updateRoutingHeaders();        
    }

    /*
     * Associate certificates with addresses.
     */
    protected void bindAddresses(OutgoingMessage message)
    {
        //
        // Retrieving the sender's private certificate is requied for encryption
        //
    	Collection<X509Certificate> privateCerts = this.resolvePrivateCerts(message.getSender(), true);
    	if (privateCerts == null || privateCerts.size() == 0)
    		LOGGER.warn("bindAddresses(OutgoingMessage message) - Could not resolve a private certificate for sender " + message.getSender().getAddress());
    	message.getSender().setCertificates(privateCerts);
    	
    	Collection<X509Certificate> anchors = this.trustAnchors.getOutgoingAnchors().getCertificates(message.getSender());
        if (anchors == null || anchors.size() == 0)
        	LOGGER.warn("bindAddresses(OutgoingMessage message) - Could not obtain outgoing trust anchors for sender " + message.getSender().getAddress());    	
        message.getSender().setTrustAnchors(anchors);

        //
        // Bind each recipient's certs
        //
        for(NHINDAddress recipient : message.getRecipients())
        {
        	Collection<X509Certificate> publicCerts = this.resolvePublicCerts(recipient, false);
        	if (publicCerts == null || publicCerts.size() == 0)
        		LOGGER.warn("bindAddresses(OutgoingMessage message) - Could not resolve a public certificate for recipient " + recipient.getAddress());
            recipient.setCertificates(publicCerts);
        }
    }

    /*
     * Wrap the message in an RFC 822 message.
     */
    protected Message wrapMessage(String messageText)
    {
    	Message retVal = null;
    	try
    	{
    		if (!wrappingEnabled)
    		{
    			return new Message(EntitySerializer.Default.deserialize(messageText));
    		}
        
    		retVal  = WrappedMessage.create(messageText, NHINDStandard.MailHeadersUsed);
    	}
    	catch (MessagingException e)
    	{
    		throw new MimeException(MimeError.InvalidMimeEntity, e);
    	}
    	
    	return retVal;
    }

    /*
     * Wrap the message in an RFC 822 message.
     */
    protected Message wrapMessage(Message message)
    {
    	
    	Message retVal = null;
    	try
    	{
	        if (!wrappingEnabled)
	        {
	            return message;
	        }
	        
	        if (WrappedMessage.isWrapped(message))
	        {
	            return message;
	        }
	        
	        retVal =  WrappedMessage.create(message, NHINDStandard.MailHeadersUsed);
    	}
    	catch (MessagingException e)
    	{
    		throw new MimeException(MimeError.InvalidMimeEntity, e);
    	}
    	
    	return retVal;
    }
    
    /*
     * Unwrap the message from an RFC 822 message.
     */
    protected Message unwrapMessage(Message message)
    {
        if (!wrappingEnabled)
        {
            return message;
        }
        
        Message retMessage = null;
                
        try
        {

            if (!WrappedMessage.isWrapped(message))
            {
                return message;
            }
        	retMessage = WrappedMessage.extract(message);
        }
        catch (MessagingException e)
        {
        	throw new MimeException(MimeError.InvalidMimeEntity, e);
        }
        
        return retMessage;
    }
    
    //
    // First sign, THEN encrypt the message
    //
    @SuppressWarnings("unchecked")
    protected void signAndEncryptMessage(OutgoingMessage message)
    {
        SignedEntity signedEntity = cryptographer.sign(message.getMessage(), message.getSender().getCertificates());
               
        try
        {
	        if (encryptionEnabled)
	        {	        	
	            MimeEntity encryptedEntity = cryptographer.encrypt(signedEntity.getMimeMultipart(), message.getRecipients().getCertificates());
	            //
	            // Alter message content to contain encrypted data
	            //
	            
	            InternetHeaders headers = new InternetHeaders();
	            Enumeration<Header> eHeaders = message.getMessage().getAllHeaders();
	            while (eHeaders.hasMoreElements())
	            {
	            	Header hdr = eHeaders.nextElement();
	            	headers.setHeader(hdr.getName(), hdr.getValue());
	            }    
	              
	            eHeaders = encryptedEntity.getAllHeaders();
	            while (eHeaders.hasMoreElements())
	            {
	            	Header hdr = (Header)eHeaders.nextElement();
	            	headers.setHeader(hdr.getName(), hdr.getValue());
	            }    	            

	            Message msg = new Message(headers, encryptedEntity.getContentAsBytes());
	            
	            message.setMessage(msg);
	        }
	        else
	        {      	
	            
	            InternetHeaders headers = new InternetHeaders();
	            Enumeration<Header> eHeaders = message.getMessage().getAllHeaders();
	            while (eHeaders.hasMoreElements())
	            {
	            	Header hdr = eHeaders.nextElement();
	            	headers.setHeader(hdr.getName(), hdr.getValue());
	            }    
	              
	            headers.setHeader(MimeStandard.ContentTypeHeader, signedEntity.getMimeMultipart().getContentType());
	            Message msg = new Message(headers, signedEntity.getEntityBodyAsBytes());	  
	            
	            message.setMessage(msg);
	         }
        }
        catch (Exception e)
        {
        	throw new MimeException(MimeError.InvalidMimeEntity, e);
        }
    }

    /*
     * Get the private certificates for the address
     */
    private Collection<X509Certificate> resolvePrivateCerts(InternetAddress address, boolean required)
    {
    	Collection<X509Certificate> certs = null;
        try
        {
            certs = this.privateCertResolver.getCertificates(address);
            if (certs == null && required)
            {
                throw new AgentException(AgentError.UntrustedSender);
            }
        }
        catch (Exception ex)
        {
        	LOGGER.warn("Exception thrown resolving private certs for address " + address.getAddress(), ex);
            if (required)
            {
            	// for logging, tracking etc...
            	throw new NHINDException(ex);
            }
        }

        return certs;
    }

    /*
     * Get the public certificates for the address
     */
    private Collection<X509Certificate> resolvePublicCerts(InternetAddress address, boolean required) throws NHINDException
    {
    	Collection<X509Certificate> certs = null;
        try
        {
            // try each resolver until it's found
        	for (CertificateResolver publicResolver : publicCertResolver)
        	{
        		certs = publicResolver.getCertificates(address);
        		if (certs != null)
        			break;
        	}
        	
            if (certs == null && required)
            {
                throw new AgentException(AgentError.UnknownRecipient);
            }
        }
        catch (Exception ex)
        {
        	LOGGER.warn("Exception thrown resolving public certs for address " + address.getAddress(), ex);
            if (required)
            {
            	// for logging, tracking etc...
            	throw new NHINDException(ex);
            }                
        }

        return certs;
    }

    
    protected void checkEnvelopeAddresses(MessageEnvelope envelope)
    {
        this.checkEnvelopeAddresses(envelope.getRecipients(), envelope.getSender());
    }
    
    protected void checkEnvelopeAddresses(NHINDAddressCollection recipients, NHINDAddress sender)
    {
        if (recipients == null || recipients.size() == 0)
        {
            throw new AgentException(AgentError.NoRecipients);
        }
        if (sender == null)
        {
            throw new AgentException(AgentError.NoSender);
        }
        
        recipients.setSource(AddressSource.RcptTo);
        sender.setSource(AddressSource.MailFrom);
    }    
}
