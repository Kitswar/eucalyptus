/*************************************************************************
 * Copyright 2009-2013 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.images;

import static com.eucalyptus.util.Parameters.checkParam;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.persistence.PersistenceException;

import org.apache.log4j.Logger;

import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.principal.AccountFullName;
import com.eucalyptus.cloud.CloudMetadatas;
import com.eucalyptus.cloud.ImageMetadata;
import com.eucalyptus.cloud.ImageMetadata.DeviceMappingType;
import com.eucalyptus.cluster.Cluster;
import com.eucalyptus.cluster.Clusters;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.Topology;
import com.eucalyptus.component.id.ClusterController;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.context.IllegalContextAccessException;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.entities.Transactions;
import com.eucalyptus.images.ImageManifests.ImageManifest;
import com.eucalyptus.records.Logs;
import com.eucalyptus.tags.Filter;
import com.eucalyptus.tags.Filters;
import com.eucalyptus.tags.Tag;
import com.eucalyptus.tags.TagSupport;
import com.eucalyptus.tags.Tags;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.RestrictedTypes;
import com.eucalyptus.vm.VmInstance;
import com.eucalyptus.vm.VmInstance.VmState;
import com.eucalyptus.vm.VmInstances;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.ucsb.eucalyptus.msgs.BlockDeviceMappingItemType;
import edu.ucsb.eucalyptus.msgs.ConfirmProductInstanceResponseType;
import edu.ucsb.eucalyptus.msgs.ConfirmProductInstanceType;
import edu.ucsb.eucalyptus.msgs.CreateImageResponseType;
import edu.ucsb.eucalyptus.msgs.CreateImageType;
import edu.ucsb.eucalyptus.msgs.DeregisterImageResponseType;
import edu.ucsb.eucalyptus.msgs.DeregisterImageType;
import edu.ucsb.eucalyptus.msgs.DescribeImageAttributeResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeImageAttributeType;
import edu.ucsb.eucalyptus.msgs.DescribeImagesResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeImagesType;
import edu.ucsb.eucalyptus.msgs.EbsDeviceMapping;
import edu.ucsb.eucalyptus.msgs.ImageDetails;
import edu.ucsb.eucalyptus.msgs.LaunchPermissionItemType;
import edu.ucsb.eucalyptus.msgs.ModifyImageAttributeResponseType;
import edu.ucsb.eucalyptus.msgs.ModifyImageAttributeType;
import edu.ucsb.eucalyptus.msgs.RegisterImageResponseType;
import edu.ucsb.eucalyptus.msgs.RegisterImageType;
import edu.ucsb.eucalyptus.msgs.ResetImageAttributeResponseType;
import edu.ucsb.eucalyptus.msgs.ResetImageAttributeType;
import edu.ucsb.eucalyptus.msgs.ResourceTag;

public class ImageManager {
  
  public static Logger        LOG = Logger.getLogger( ImageManager.class );
  
  private static final String ADD = "add";
  
  public DescribeImagesResponseType describe( final DescribeImagesType request ) throws EucalyptusCloudException, TransactionException {
    DescribeImagesResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup( );
    final String requestAccountId = ctx.getUserFullName( ).getAccountNumber( );
    final List<String> ownersSet = request.getOwnersSet();
    if ( ownersSet.remove( Images.SELF ) ) {
      ownersSet.add( requestAccountId );
    }
    final Filter filter = Filters.generate( request.getFilterSet(), ImageInfo.class );
    final Predicate<? super ImageInfo> requestedAndAccessible = CloudMetadatas.filteringFor( ImageInfo.class )
        .byId( request.getImagesSet() )
        .byOwningAccount( request.getOwnersSet() )
        .byPredicate( Images.filterExecutableBy( request.getExecutableBySet() ) )
        .byPredicate( filter.asPredicate() )
        .byPredicate( Images.FilterPermissions.INSTANCE )
        .byPrivilegesWithoutOwner()
        .buildPredicate();
    final List<ImageDetails> imageDetailsList = Transactions.filteredTransform(
        new ImageInfo(),
        filter.asCriterion(),
        filter.getAliases(),
        requestedAndAccessible,
        Images.TO_IMAGE_DETAILS );

    final Map<String,List<Tag>> tagsMap = TagSupport.forResourceClass( ImageInfo.class )
        .getResourceTagMap( AccountFullName.getInstance( ctx.getAccount() ),
            Iterables.transform( imageDetailsList, ImageDetailsToImageId.INSTANCE ) );
    for ( final ImageDetails details : imageDetailsList ) {
      Tags.addFromTags( details.getTagSet(), ResourceTag.class, tagsMap.get( details.getImageId() ) );
    }

    reply.getImagesSet( ).addAll( imageDetailsList );
    ImageUtil.cleanDeregistered();
    return reply;
  }
  
  public RegisterImageResponseType register( final RegisterImageType request ) throws EucalyptusCloudException, AuthException, IllegalContextAccessException, NoSuchElementException, PersistenceException {
	final Context ctx = Contexts.lookup();
    ImageInfo imageInfo = null;
    final String rootDevName = ( request.getRootDeviceName( ) != null )
      ? request.getRootDeviceName( )
      : Images.DEFAULT_ROOT_DEVICE;
    final String eki = request.getKernelId( );
    final String eri = request.getRamdiskId( );
    
    if ( request.getImageLocation( ) != null ) {
      // Verify all the device mappings first.
      bdmInstanceStoreImageVerifier( ).apply( request );
      
      //When there is more than one verifier, something like this can be handy: Predicates.and(bdmVerifier(Boolean.FALSE)...).apply(request);
      
      final ImageManifest manifest = ImageManifests.lookup( request.getImageLocation( ) );
      LOG.debug( "Obtained manifest information for requested image registration: " + manifest );
      List<DeviceMapping> vbr = Lists.transform( request.getBlockDeviceMappings( ), Images.deviceMappingGenerator( imageInfo, null ) );
      final ImageMetadata.Architecture arch = ( request.getArchitecture( ) == null
        ? null
        : ImageMetadata.Architecture.valueOf( request.getArchitecture( ) ) );
      Supplier<ImageInfo> allocator = new Supplier<ImageInfo>( ) {
        
        @Override
        public ImageInfo get( ) {
          try {
            return Images.createFromManifest( ctx.getUserFullName( ), request.getName( ), request.getDescription( ), arch, eki, eri, manifest );
          } catch ( Exception ex ) {
            LOG.error( ex );
            Logs.extreme( ).error( ex, ex );
            throw Exceptions.toUndeclared( ex );
          }
        }
      };
      imageInfo = RestrictedTypes.allocateUnitlessResource( allocator );
      imageInfo.getDeviceMappings( ).addAll( vbr );
    } else if ( rootDevName != null && Iterables.any( request.getBlockDeviceMappings( ), Images.findEbsRoot( rootDevName ) ) ) {
      // Verify all the device mappings first. Dont fuss if both snapshot id and volume size are left blank
      bdmBfebsImageVerifier( ).apply( request );
      
      Supplier<ImageInfo> allocator = new Supplier<ImageInfo>( ) {
        
        @Override
        public ImageInfo get( ) {
          try {
            return Images.createFromDeviceMapping( ctx.getUserFullName( ), request.getName( ),
                                                   request.getDescription( ), eki, eri, rootDevName,
                                                   request.getBlockDeviceMappings( ) );
          } catch ( EucalyptusCloudException ex ) {
            throw new RuntimeException( ex );
          }
        }
      };
      imageInfo = RestrictedTypes.allocateUnitlessResource( allocator );
    } else {
      throw new EucalyptusCloudException( "Invalid request:  the request must specify either ImageLocation for an " +
                                          "instance-store image or a snapshot for the root device for an EBS image.  " +
                                          "Provided values were: ImageLocation=" + request.getImageLocation( ) +
                                          " BlockDeviceMappings=" + request.getBlockDeviceMappings( ) );
    }
    
    RegisterImageResponseType reply = ( RegisterImageResponseType ) request.getReply( );
    reply.setImageId( imageInfo.getDisplayName( ) );
    return reply;
  }
  
  public DeregisterImageResponseType deregister( DeregisterImageType request ) throws EucalyptusCloudException {
    DeregisterImageResponseType reply = request.getReply( );
    final Context ctx = Contexts.lookup( );
    final String requestAccountId = ctx.getUserFullName( ).getAccountNumber( );

    EntityWrapper<ImageInfo> db = EntityWrapper.get( ImageInfo.class );
    try {
      ImageInfo imgInfo = db.getUnique( Images.exampleWithImageId( imageIdentifier( request.getImageId( ) ) ) );
      if ( !ctx.hasAdministrativePrivileges( ) &&
           ( !imgInfo.getOwnerAccountNumber( ).equals( requestAccountId ) ||
               !RestrictedTypes.filterPrivileged( ).apply( imgInfo ) ) ) {
        throw new EucalyptusCloudException( "Not authorized to deregister image" );
      }
      Images.deregisterImage( imgInfo.getDisplayName( ) );
      return reply;
    } catch ( NoSuchImageException ex ) {
      LOG.trace( ex );
      reply.set_return( false );
      return reply;
    } catch ( NoSuchElementException ex ) {
      LOG.trace( ex );
      reply.set_return( false );
      return reply;
    } catch ( InstanceNotTerminatedException re ) {
      throw new EucalyptusCloudException( re );
    } catch ( EucalyptusCloudException ex ) {
      if ( ex.getCause() instanceof NoSuchElementException )
        throw new EucalyptusCloudException( "The image id '[" +  request.getImageId( ) + "]' does not exist");
      else throw ex;
    } finally {
      db.commit( );
    }
  }
  
  public ConfirmProductInstanceResponseType confirmProductInstance( ConfirmProductInstanceType request ) throws EucalyptusCloudException {
    ConfirmProductInstanceResponseType reply = ( ConfirmProductInstanceResponseType ) request.getReply( );
    reply.set_return( false );
    VmInstance vm = null;
    try {
      vm = VmInstances.lookup( request.getInstanceId( ) );
//ASAP: FIXME: GRZE: RESTORE!
//      EntityWrapper<ImageInfo> db = EntityWrapper.get( ImageInfo.class );
//      try {
//        ImageInfo found = db.getUnique( new ImageInfo( vm.getImageInfo( ).getImageId( ) ) );
//        if ( found.getProductCodes( ).contains( new ProductCode( request.getProductCode( ) ) ) ) {
//          reply.set_return( true );
//          reply.setOwnerId( found.getImageOwnerId( ) );
//        }
//        db.commit( );
//      } catch ( EucalyptusCloudException e ) {
//        db.commit( );
//      }
    } catch ( NoSuchElementException e ) {}
    return reply;
  }

  public DescribeImageAttributeResponseType describeImageAttribute( final DescribeImageAttributeType request ) throws EucalyptusCloudException {
    DescribeImageAttributeResponseType reply = ( DescribeImageAttributeResponseType ) request.getReply( );
    reply.setImageId( request.getImageId( ) );
    final Context ctx = Contexts.lookup();
    final String requestAccountId = ctx.getUserFullName( ).getAccountNumber();

    if ( request.getAttribute( ) != null ) request.applyAttribute( );
    
    final EntityWrapper<ImageInfo> db = EntityWrapper.get( ImageInfo.class );
    try {
      final ImageInfo imgInfo = db.getUnique( Images.exampleWithImageId( imageIdentifier( request.getImageId( ) ) ) );
      if ( !ctx.hasAdministrativePrivileges( ) &&
           ( !imgInfo.getOwnerAccountNumber( ).equals( requestAccountId ) || !RestrictedTypes.filterPrivileged( ).apply( imgInfo ) ) ) {
        throw new EucalyptusCloudException( "Not authorized to describe image attribute" );
      }
      if ( request.getKernel( ) != null ) {
        reply.setRealResponse( reply.getKernel( ) );
        if ( imgInfo instanceof MachineImageInfo ) {
          if ( ( ( MachineImageInfo ) imgInfo ).getKernelId( ) != null ) {
            reply.getKernel( ).add( ( ( MachineImageInfo ) imgInfo ).getKernelId( ) );
          }
        }
      } else if ( request.getRamdisk( ) != null ) {
        reply.setRealResponse( reply.getRamdisk( ) );
        if ( imgInfo instanceof MachineImageInfo ) {
          if ( ( ( MachineImageInfo ) imgInfo ).getRamdiskId( ) != null ) {
            reply.getRamdisk( ).add( ( ( MachineImageInfo ) imgInfo ).getRamdiskId( ) );
          }
        }
      } else if ( request.getLaunchPermission( ) != null ) {
        reply.setRealResponse( reply.getLaunchPermission( ) );
        if ( imgInfo.getImagePublic( ) ) {
          reply.getLaunchPermission( ).add( LaunchPermissionItemType.newGroupLaunchPermission() );
        }
        for ( final String permission : imgInfo.getPermissions() )
          reply.getLaunchPermission().add( LaunchPermissionItemType.newUserLaunchPermission( permission ) );
      } else if ( request.getProductCodes( ) != null ) {
        reply.setRealResponse( reply.getProductCodes( ) );
        reply.getProductCodes( ).addAll( imgInfo.getProductCodes( ) );
      } else if ( request.getBlockDeviceMapping( ) != null ) {
    	reply.setRealResponse( reply.getBlockDeviceMapping( ) );
    	if ( imgInfo instanceof BlockStorageImageInfo ) {
    	  BlockStorageImageInfo bfebsImage = (BlockStorageImageInfo) imgInfo;
    	  reply.getBlockDeviceMapping( ).add( new BlockDeviceMappingItemType( "emi", bfebsImage.getRootDeviceName( ) ) );
    	  reply.getBlockDeviceMapping( ).add( new BlockDeviceMappingItemType( "root", bfebsImage.getRootDeviceName( ) ) );
    	  int i = 0;
    	  for ( DeviceMapping mapping : bfebsImage.getDeviceMappings() ) {
    	    if ( mapping.getDeviceName( ).equalsIgnoreCase( bfebsImage.getRootDeviceName( ) ) ) {
    		  continue;
    		}
    	    switch ( mapping.getDeviceMappingType( ) ) {
			  case blockstorage:
				BlockStorageDeviceMapping bsdm = ( BlockStorageDeviceMapping ) mapping;
	    		BlockDeviceMappingItemType bdmItem = new BlockDeviceMappingItemType( "ebs" + (++i), mapping.getDeviceName( ) );
	    		EbsDeviceMapping ebsItem = new EbsDeviceMapping( );
	    		ebsItem.setSnapshotId( bsdm.getSnapshotId( ) );
	    		ebsItem.setVolumeSize( bsdm.getSize( ) );
	    		ebsItem.setDeleteOnTermination( bsdm.getDelete( ) );
	    		bdmItem.setEbs( ebsItem );
	    		reply.getBlockDeviceMapping( ).add( bdmItem );
				break;
			  case ephemeral:
				reply.getBlockDeviceMapping( ).add( new BlockDeviceMappingItemType( mapping.getVirtualName() , mapping.getDeviceName( ) ) ); 
				break;
		      default:
				break;
    	    }
    	  }
    	} else {
          reply.getBlockDeviceMapping( ).add( ImageUtil.EMI );
          reply.getBlockDeviceMapping( ).add( ImageUtil.EPHEMERAL );
          reply.getBlockDeviceMapping( ).add( ImageUtil.SWAP );
          reply.getBlockDeviceMapping( ).add( ImageUtil.ROOT );
    	}
      } else if ( request.getDescription( ) != null ) {
        reply.setRealResponse( reply.getDescription( ) );
        if ( imgInfo.getDescription() != null ) {
          reply.getDescription().add( imgInfo.getDescription() );
        }
      } else {
        throw new EucalyptusCloudException( "invalid image attribute request." );
      }
    } finally {
      db.commit( );
    }
    return reply;
  }

  private static List<String> verifyUserIds(final List<String> userIds) throws EucalyptusCloudException {
    final Set<String> validUserIds = Sets.newHashSet( );
    for ( String userId : userIds ) {
      try {
        validUserIds.add( Accounts.lookupAccountById( userId ).getAccountNumber() );
      } catch ( final Exception e ) {
        try {
          validUserIds.add( Accounts.lookupUserById( userId ).getAccount().getAccountNumber() );
        } catch ( AuthException ex ) {
          try {
            validUserIds.add( Accounts.lookupUserByAccessKeyId( userId ).getAccount().getAccountNumber() );
          } catch ( AuthException ex1 ) {
            throw new EucalyptusCloudException( "Not a valid userId : " + userId );
          }
        }
      }
    }
    return Lists.newArrayList( validUserIds );
  }

  public ModifyImageAttributeResponseType modifyImageAttribute( final ModifyImageAttributeType request ) throws EucalyptusCloudException {
    final ModifyImageAttributeResponseType reply = ( ModifyImageAttributeResponseType ) request.getReply( );
    final Context ctx = Contexts.lookup();
    final String requestAccountId = ctx.getUserFullName( ).getAccountNumber();

    final EntityWrapper<ImageInfo> db = EntityWrapper.get( ImageInfo.class );
    try {
      final ImageInfo imgInfo = db.getUnique( Images.exampleWithImageId( imageIdentifier( request.getImageId( ) ) ) );
      if ( !ctx.hasAdministrativePrivileges( ) &&
           ( !imgInfo.getOwnerAccountNumber( ).equals( requestAccountId ) ||
               !RestrictedTypes.filterPrivileged( ).apply( imgInfo ) ) ) {
        throw new EucalyptusCloudException( "Not authorized to modify image attribute" );
      }

      switch ( request.getImageAttribute() ) {
        case LaunchPermission:
          if ( request.isAdd() ) {
            imgInfo.addPermissions( verifyUserIds( request.getUserIds() ) );
            if ( request.isGroupAll() ) {
              imgInfo.setImagePublic( true );
            }
          } else {
            imgInfo.removePermissions( request.getUserIds() );
            if ( request.isGroupAll() ) {
              imgInfo.setImagePublic( false );
            }
          }
          break;
        case ProductCode:
          for ( String productCode : request.getProductCodes( ) ) {
            imgInfo.addProductCode( productCode );
          }
          break;
        case Description:
          imgInfo.setDescription( request.getDescription() );
          break;
      }

      db.commit( );
      reply.set_return( true );
    } catch ( EucalyptusCloudException e ) {
      db.rollback( );
      reply.set_return( false );
      throw e;
    }
    
    return reply;
  }
  
  public ResetImageAttributeResponseType resetImageAttribute( ResetImageAttributeType request ) throws EucalyptusCloudException {
    ResetImageAttributeResponseType reply = ( ResetImageAttributeResponseType ) request.getReply( );
    reply.set_return( true );
    final Context ctx = Contexts.lookup( );
    final String requestAccountId = ctx.getUserFullName( ).getAccountNumber( );
    EntityWrapper<ImageInfo> db = EntityWrapper.get( ImageInfo.class );
    try {
      ImageInfo imgInfo = db.getUnique( Images.exampleWithImageId( imageIdentifier( request.getImageId( ) ) ) );
      if ( ctx.hasAdministrativePrivileges( ) ||
           ( imgInfo.getOwnerAccountNumber( ).equals( requestAccountId ) &&
               RestrictedTypes.filterPrivileged( ).apply( imgInfo ) ) ) {
        imgInfo.resetPermission( );
        db.commit( );
      } else {
        db.rollback( );
        reply.set_return( false );
      }
    } catch ( EucalyptusCloudException e ) {
      LOG.error( e, e );
      db.rollback( );
      reply.set_return( false );
    }
    return reply;
  }
  
  public CreateImageResponseType createImage( CreateImageType request ) throws EucalyptusCloudException {
    CreateImageResponseType reply = request.getReply( );
    Context ctx = Contexts.lookup( );
    VmInstance vm;
    try {
      vm = RestrictedTypes.doPrivileged( request.getInstanceId( ), VmInstance.class );
      if ( !VmState.RUNNING.equals( vm.getState( ) ) && !VmState.STOPPED.equals( vm.getState( ) ) ) {
        throw new EucalyptusCloudException( "Cannot create an image from an instance which is not in either the 'running' or 'stopped' state: "
                                            + vm.getInstanceId( ) + " is in state " + vm.getState( ).getName( ) );
      }
      if ( vm.isBlockStorage( ) && !ctx.hasAdministrativePrivileges( ) ) {
        throw new EucalyptusCloudException( "Cannot create an image from an instance which is not booted from a volume: " + vm.getInstanceId( )
                                            + " is in state "
                                            + vm.getState( ).getName( ) );
      } else if ( vm.isBlockStorage( ) ) {

      } else {
        Cluster cluster = null;
        try {
          ServiceConfiguration ccConfig = Topology.lookup( ClusterController.class, vm.lookupPartition( ) );
          cluster = Clusters.lookup( ccConfig );
        } catch ( NoSuchElementException e ) {
          LOG.debug( e );
          throw new EucalyptusCloudException( "Cluster does not exist: " + vm.getPartition( )  );
        }
      }
    } catch ( AuthException ex ) {
      throw new EucalyptusCloudException( "Not authorized to create an image from instance " + request.getInstanceId( ) + " as " + ctx.getUser( ).getName( ) );
    } catch ( NoSuchElementException ex ) {
      throw new EucalyptusCloudException( "Instance does not exist: " + request.getInstanceId( ), ex );
    } catch ( PersistenceException ex ) {
      throw new EucalyptusCloudException( "Instance does not exist: " + request.getInstanceId( ), ex );
    }
    //save instance state
    //terminate the instance
    //clone the volume
    //-> start the instance
    //-> snapshot the volume
    //   |-> mark registration as available
    
    return reply;
  }

  private static String imageIdentifier( final String identifier ) throws EucalyptusCloudException {
    if( identifier == null || !Images.IMAGE_ID_PATTERN.matcher( identifier ).matches() )
      throw new EucalyptusCloudException( "Invalid id: " + "\"" + identifier + "\"" );
    return identifier;
  }

  private enum ImageDetailsToImageId implements Function<ImageDetails, String> {
    INSTANCE {
      @Override
      public String apply( ImageDetails imageDetails ) {
        return imageDetails.getImageId();
      }
    }
  }
  
  /**
   * <p>Predicate to validate the block device mappings in instance store image registration request.
   * Suppressing a device mapping is allowed and ebs mappings are considered invalid.</p>
   */
  private static Predicate<RegisterImageType> bdmInstanceStoreImageVerifier () {
    return new Predicate<RegisterImageType>( ) {
	  @Override
	  public boolean apply(RegisterImageType arg0) {
		checkParam( arg0, notNullValue( ) );
		return Images.isDeviceMappingListValid( arg0.getBlockDeviceMappings(), Boolean.TRUE, Boolean.FALSE );
	  }	  
	};
  }
  
  /**
   * <p>Predicate to validate the block device mappings in boot from ebs image registration request.
   * Suppressing a device mapping is not allowed and ebs mappings are considered valid</p>
   */
  private static Predicate<RegisterImageType> bdmBfebsImageVerifier () {
	  return new Predicate<RegisterImageType>( ) {
	  @Override
	  public boolean apply(RegisterImageType arg0) {
		checkParam( arg0, notNullValue( ) );
	    return Images.isDeviceMappingListValid( arg0.getBlockDeviceMappings(), Boolean.FALSE, Boolean.TRUE );
	  }	  
	};
  }
}
