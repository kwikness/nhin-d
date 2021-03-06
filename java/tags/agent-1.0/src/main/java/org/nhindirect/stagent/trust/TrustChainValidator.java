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

package org.nhindirect.stagent.trust;

import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.security.auth.x500.X500Principal;

import org.nhindirect.stagent.cert.CertificateResolver;
import org.nhindirect.stagent.cert.Thumbprint;

/**
 * Validates the trust chain of a certificate with a set of anchors.  If a certificate resolver is present, the validator will search
 * for intermediate certificates.
 * @author Greg Meyer
 * @author Umesh Madan
 *
 */
public class TrustChainValidator 
{
	
	private static int DefaultMaxIssuerChainLength = 5;

	private Collection<CertificateResolver> certResolvers;
	
	private int maxIssuerChainLength = DefaultMaxIssuerChainLength;
	
	/**
	 * Indicates if the TrustChainValidator has a certificate resolvers for resolving intermediates certificates.
	 * @return True is an intermediate certificate resolver is present.  False otherwise.
	 */
	public boolean isCertificateResolver()
	{
		return certResolvers != null;
	}
	
	/**
	 * Gets the intermediate certificate resolvers.  This is generally a resolver capable of resolving public certificates.
	 * @return The intermediate certificate resolvers.
	 */
	public Collection<CertificateResolver> getCertificateResolver()
	{
		return certResolvers;
	}
	
	/**
	 * Sets the intermediate certificate resolvers.  This is generally a resolver capable of resolving public certificates.
	 * @param resolver the intermediate certificate resolver.
	 */
	public void setCertificateResolver(Collection<CertificateResolver> resolver)
	{
		certResolvers = resolver;
	}
	
	/**
	 * Indicates if a certificate is considered to be trusted by resolving a valid certificate trust chain with the provided anchors.
	 * @param certificate The certificate to check.
	 * @param anchors A list of trust anchors used to check the trust chain.
	 * @return Returns true if the certificate can find a valid trust chain in the collection of anchors.  False otherwise.
	 */
    public boolean isTrusted(X509Certificate certificate, Collection<X509Certificate> anchors)
    {    	
    	if (certificate == null)
    		throw new IllegalArgumentException();
    	
    	if (anchors == null || anchors.size() == 0)
    		return false; // no anchors... conspiracy theory?  trust no one
    	
    	try
    	{
        	
    		CertPath certPath = null;
        	CertificateFactory factory = CertificateFactory.getInstance("X509");
        	
        	List<Certificate> certs = new ArrayList<Certificate>();
        	certs.add(certificate);
        	
        	// check for intermediates
        	if (certResolvers != null)
        	{
        		Collection<X509Certificate> intermediatesCerts = resolveIntermediateIssuers(certificate);
        		if (intermediatesCerts != null && intermediatesCerts.size() > 0)
        			certs.addAll(intermediatesCerts);
        	}
        	
        	Set<TrustAnchor> trustAnchorSet = new HashSet<TrustAnchor>();
        		
        	for (X509Certificate archor : anchors)
        		trustAnchorSet.add(new TrustAnchor(archor, null));
        	
            PKIXParameters params = new PKIXParameters(trustAnchorSet); 
            params.setRevocationEnabled(false); // NHIND Revocations are handled using CertificateStore.getCertificate
        	certPath = factory.generateCertPath(certs);
        	CertPathValidator pathValidator = CertPathValidator.getInstance("PKIX", "BC");    		
    		

        	pathValidator.validate(certPath, params);
    		return true;
    	}
    	catch (Exception e)
    	{
    		e.printStackTrace();
    	}
    	
    	return false;    	
    }     	
    
    private Collection<X509Certificate> resolveIntermediateIssuers(X509Certificate certificate)
    {
    	Collection<X509Certificate> issuers = new ArrayList<X509Certificate>();
        resolveIntermediateIssuers(certificate, issuers);
        return issuers;
    }   
    
    private void resolveIntermediateIssuers(X509Certificate certificate, /*in-out*/Collection<X509Certificate> issuers)
    {
        if (certificate == null)
        {
            throw new IllegalArgumentException("Certificate cannot be null.");
        }
        if (issuers == null)
        {
        	throw new IllegalArgumentException("Issuers collection cannot be null.");
        }
        
        resolveIssuers(certificate, issuers, 0);
    }       
    
    private boolean isIssuerInCollection(Collection<X509Certificate> issuers, X509Certificate checkIssuer)
    {
    	for (X509Certificate issuer : issuers)
    	{
    		if (checkIssuer.equals(issuer.getSubjectX500Principal()) && Thumbprint.toThumbprint(issuer).equals(Thumbprint.toThumbprint(checkIssuer)))
    			return true; // already found the certificate issuer... done
    	}
    	return false;
    }
    
    private void resolveIssuers(X509Certificate certificate, /*in-out*/Collection<X509Certificate> issuers, int chainLength)
    {
    	
    	X500Principal issuerPrin = certificate.getIssuerX500Principal();
    	if (issuerPrin.equals(certificate.getSubjectX500Principal()))
    	{
    		// I am my own issuer... self signed cert
    		// no intermediate between me, myself, and I
    		return;
    	}
    	
    	// look in the issuer list and see if the certificate issuer already exists in the list
    	for (X509Certificate issuer : issuers)
    	{
    		if (issuerPrin.equals(issuer.getSubjectX500Principal()))
    			return; // already found the certificate issuer... done
    	}
    	
    	if (chainLength >= maxIssuerChainLength)
    	{
    		// can't go any further than the max number of links in the chain.
    		// bail out with what we have now
    		return;
    	}
    	
    	String address = this.getIssuerAddress(issuerPrin);

    	if (address == null || address.isEmpty())
    		return;// not much we can do about this... the resolver interface only knows how to work with addresses
    	
		Collection<X509Certificate> issuerCerts = new ArrayList<X509Certificate>();
		
		// look in each resolver...  the list could be blasted across 
		// multiple resolvers
    	for (CertificateResolver publicResolver : certResolvers)
    	{
    		try
    		{	
    			Collection<X509Certificate> holdCerts = publicResolver.getCertificates(new InternetAddress(address));
    			if (holdCerts != null && holdCerts.size() > 0)
    				issuerCerts.addAll(holdCerts);
    		}
    		catch (AddressException e)
    		{
    			// no-op
    		}
        }

		
		if (issuerCerts.size() == 0)
			return; // no intermediates.. just return
		
		for (X509Certificate issuerCert : issuerCerts)
		{
			if (issuerCert.getSubjectX500Principal().equals(issuerPrin) && !isIssuerInCollection(issuers, issuerCert))
			{
				issuers.add(issuerCert);
				
				// see if this issuer also has intermediate certs
				resolveIssuers(issuerCert, issuers, chainLength + 1);
			}
		}
    }
    
    private String getIssuerAddress(X500Principal issuerPrin)
    {
    	// get the domain name
		Map<String, String> oidMap = new HashMap<String, String>();
		oidMap.put("1.2.840.113549.1.9.1", "EMAILADDRESS");  // OID for email address
		String prinName = issuerPrin.getName(X500Principal.RFC1779, oidMap);    
		
		// see if there is an email address first in the DN
		String searchString = "EMAILADDRESS=";
		int index = prinName.indexOf(searchString);
		if (index == -1)
		{
			searchString = "CN=";
			// no Email.. check the CN
			index = prinName.indexOf(searchString);
			if (index == -1)
				return ""; // no CN... nothing else that can be done from here
		}
		
		// look for a "," to find the end of this attribute
		int endIndex = prinName.indexOf(",", index);
		String address;
		if (endIndex > -1)
			address = prinName.substring(index + searchString.length(), endIndex);
		else 
			address= prinName.substring(index + searchString.length());
		
		return address;
    }
}
