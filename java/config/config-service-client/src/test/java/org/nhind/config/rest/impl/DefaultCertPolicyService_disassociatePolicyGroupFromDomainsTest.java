package org.nhind.config.rest.impl;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;

import org.junit.Test;
import org.nhind.config.client.ConfigServiceRunner;
import org.nhind.config.rest.CertPolicyService;
import org.nhind.config.rest.DomainService;
import org.nhind.config.testbase.BaseTestPlan;

import org.nhindirect.common.rest.exceptions.ServiceException;
import org.nhindirect.common.rest.exceptions.ServiceMethodException;
import org.nhindirect.config.model.Address;
import org.nhindirect.config.model.CertPolicy;
import org.nhindirect.config.model.CertPolicyGroup;
import org.nhindirect.config.model.Domain;
import org.nhindirect.config.model.EntityStatus;
import org.nhindirect.config.resources.CertPolicyResource;
import org.nhindirect.config.store.dao.CertPolicyDao;
import org.nhindirect.config.store.dao.DomainDao;

public class DefaultCertPolicyService_disassociatePolicyGroupFromDomainsTest 
{
    protected CertPolicyDao policyDao;
    
    protected DomainDao domainDao;
    
	static CertPolicyService resource;
	static DomainService domainResource;	
	
	abstract class TestPlan extends BaseTestPlan 
	{
		protected Collection<CertPolicyGroup> groups;
		
		protected Collection<CertPolicy> policies;
		
		@Override
		protected void setupMocks()
		{
			try
			{
				policyDao = (CertPolicyDao)ConfigServiceRunner.getSpringApplicationContext().getBean("certPolicyDao");
				domainDao =  (DomainDao)ConfigServiceRunner.getSpringApplicationContext().getBean("domainDao");
				
				resource = 	(CertPolicyService)BaseTestPlan.getService(ConfigServiceRunner.getRestAPIBaseURL(), CERT_POLICY_SERVICE);	
				domainResource = 	(DomainService)BaseTestPlan.getService(ConfigServiceRunner.getRestAPIBaseURL(), DOMAIN_SERVICE);	
				
			}
			catch (Throwable t)
			{
				throw new RuntimeException(t);
			}
		}
		
		@Override
		protected void tearDownMocks()
		{

		}
		
		protected  Collection<CertPolicyGroup> getGroupsToAdd()
		{
			try
			{
				groups = new ArrayList<CertPolicyGroup>();
				
				CertPolicyGroup group = new CertPolicyGroup();
				group.setPolicyGroupName("Group1");
				groups.add(group);
				
				group = new CertPolicyGroup();
				group.setPolicyGroupName("Group2");
				groups.add(group);
				
				return groups;
			}
			catch (Exception e)
			{
				throw new RuntimeException (e);
			}
		}
		
		
		protected  Domain getDomainToAdd()
		{
			final Address postmasterAddress = new Address();
			postmasterAddress.setEmailAddress("me@test.com");
			
			Domain domain = new Domain();
			
			domain.setDomainName("test.com");
			domain.setStatus(EntityStatus.ENABLED);
			domain.setPostmasterAddress(postmasterAddress);			
			
			return domain;
		}
		
		
		
		protected  String getGroupNameToAssociate()
		{
			return "Group1";
		}
		
		protected  String getDomainNameToAssociate()
		{
			return "test.com";
		}
		
		protected abstract String getGroupNameToDisassociate();
		
		
		@Override
		protected void performInner() throws Exception
		{				
			final Domain addDomain = getDomainToAdd();
			
			if (addDomain != null)
			{
				try
				{
					domainResource.addDomain(addDomain);
				}
				catch (ServiceException e)
				{
					throw e;
				}
			}
			
			final Collection<CertPolicyGroup> groupsToAdd = getGroupsToAdd();
			
			if (groupsToAdd != null)
			{
				for (CertPolicyGroup addGroup : groupsToAdd)
				{
					try
					{
						resource.addPolicyGroup(addGroup);
					}
					catch (ServiceException e)
					{
						throw e;
					}
				}
			}
			

			// associate the bundle and domain
			if (groupsToAdd != null && addDomain != null)
				resource.associatePolicyGroupToDomain(getGroupNameToAssociate(), getDomainNameToAssociate());
			
			// disassociate
			resource.disassociatePolicyGroupFromDomains(getGroupNameToDisassociate());
			
			doAssertions();
			
		}
			
		protected void doAssertions() throws Exception
		{
			
		}
	}
	
	@Test
	public void testDisassociatePolicyGroupFromDomains_assertGroupDomainDisassociated()  throws Exception
	{
		new TestPlan()
		{

			@Override
			protected String getGroupNameToDisassociate()
			{
				return "Group1";
			}
			
			
			@Override
			protected void doAssertions() throws Exception
			{
				final org.nhindirect.config.store.Domain domain = domainDao.getDomainByName(getDomainNameToAssociate());
				
				final Collection<org.nhindirect.config.store.CertPolicyGroupDomainReltn> reltns = policyDao.getPolicyGroupsByDomain(domain.getId());
				
				assertEquals(0, reltns.size());
			}
		}.perform();
	}	
	
	@Test
	public void testDisassociatePolicyGroupFromDomains_unknownGroup_assertNotFound()  throws Exception
	{
		new TestPlan()
		{

			@Override
			protected String getGroupNameToDisassociate()
			{
				return "Group4";
			}
			
			
			@Override
			protected void assertException(Exception exception) throws Exception 
			{
				assertTrue(exception instanceof ServiceMethodException);
				ServiceMethodException ex = (ServiceMethodException)exception;
				assertEquals(404, ex.getResponseCode());
			}
		}.perform();
	}		
	
	@Test
	public void testDisassociatePolicyGroupFromDomains_errorInGroupLookup_assertServiceError()  throws Exception
	{
		new TestPlan()
		{

			protected CertPolicyResource certService;
			
			@Override
			protected void setupMocks()
			{
				try
				{
					super.setupMocks();
					
					certService = (CertPolicyResource)ConfigServiceRunner.getSpringApplicationContext().getBean("certPolicyResource");

					CertPolicyDao mockDAO = mock(CertPolicyDao.class);
					doThrow(new RuntimeException()).when(mockDAO).getPolicyGroupByName((String)any());
					
					certService.setCertPolicyDao(mockDAO);
				}
				catch (Throwable t)
				{
					throw new RuntimeException(t);
				}
			}
			
			@Override
			protected void tearDownMocks()
			{
				super.tearDownMocks();
				
				certService.setCertPolicyDao(policyDao);
			}
			
			@Override
			protected  Collection<CertPolicyGroup> getGroupsToAdd()
			{
				return null;
			}
			
			@Override
			protected  Domain getDomainToAdd()
			{
				return null;
			}
			
			@Override
			protected String getGroupNameToDisassociate()
			{
				return "Group1";
			}
			
			
			@Override
			protected void assertException(Exception exception) throws Exception 
			{
				assertTrue(exception instanceof ServiceMethodException);
				ServiceMethodException ex = (ServiceMethodException)exception;
				assertEquals(500, ex.getResponseCode());
			}
		}.perform();
	}	
	
	@Test
	public void testDisassociatePolicyGroupFromDomains_errorInDisassociate_assertServiceError()  throws Exception
	{
		new TestPlan()
		{
			protected CertPolicyResource certService;
			
			@Override
			protected void setupMocks()
			{
				try
				{
					super.setupMocks();
					
					certService = (CertPolicyResource)ConfigServiceRunner.getSpringApplicationContext().getBean("certPolicyResource");

					CertPolicyDao mockPolicyDAO = mock(CertPolicyDao.class);
					DomainDao mockDomainDAO = mock(DomainDao.class);
					
					when(mockPolicyDAO.getPolicyGroupByName("Group1")).thenReturn(new org.nhindirect.config.store.CertPolicyGroup());
					doThrow(new RuntimeException()).when(mockPolicyDAO).disassociatePolicyGroupFromDomains(eq(0L));
					
					certService.setCertPolicyDao(mockPolicyDAO);
					certService.setDomainDao(mockDomainDAO);
				}
				catch (Throwable t)
				{
					throw new RuntimeException(t);
				}
			}
			
			@Override
			protected void tearDownMocks()
			{
				super.tearDownMocks();
				
				certService.setCertPolicyDao(policyDao);
				certService.setDomainDao(domainDao);
			}
			
			@Override
			protected  Collection<CertPolicyGroup> getGroupsToAdd()
			{
				return null;
			}
			
			@Override
			protected  Domain getDomainToAdd()
			{
				return null;
			}
			
			@Override
			protected String getGroupNameToDisassociate()
			{
				return "Group1";
			}
			
			@Override
			protected void assertException(Exception exception) throws Exception 
			{
				assertTrue(exception instanceof ServiceMethodException);
				ServiceMethodException ex = (ServiceMethodException)exception;
				assertEquals(500, ex.getResponseCode());
			}
		}.perform();
	}			
}

