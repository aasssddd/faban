/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: Master.java,v 1.2 2006/06/29 19:38:37 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.driver.core;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface for calling back to the master.
 *
 * @author Akara Sucharitakul
 */
public interface Master extends Remote {

    /**
     * Obtain the master's time for time adjustment.
     * @return The current time on the master
     * @throws RemoteException A network error occurred
     */
    long currentTimeMillis() throws RemoteException;

    /**
     * Notifies the master to terminate the run immediately.
     * This usually happens if there is a fatal error in the run.
     * @throws RemoteException A network error occurred.
     */
    void abortRun() throws RemoteException;

}