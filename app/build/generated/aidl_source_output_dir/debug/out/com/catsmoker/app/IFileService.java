/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\\Users\\Lenovo\\AppData\\Local\\Android\\Sdk\\build-tools\\36.0.0\\aidl.exe -pC:\\Users\\Lenovo\\AppData\\Local\\Android\\Sdk\\platforms\\android-37.0\\framework.aidl -oC:\\Users\\Lenovo\\StudioProjects\\com.catsmoker.app\\app\\build\\generated\\aidl_source_output_dir\\debug\\out -IC:\\Users\\Lenovo\\StudioProjects\\com.catsmoker.app\\app\\src\\main\\aidl -IC:\\Users\\Lenovo\\StudioProjects\\com.catsmoker.app\\app\\src\\debug\\aidl -IC:\\Users\\Lenovo\\.gradle\\caches\\9.6.1\\transforms\\893e0187c32d62cb5ebb05b8ddccb141\\transformed\\core-1.19.0\\aidl -IC:\\Users\\Lenovo\\.gradle\\caches\\9.6.1\\transforms\\4b170c51eefa1ace3885a770c671efab\\transformed\\versionedparcelable-1.1.1\\aidl -dC:\\Users\\Lenovo\\AppData\\Local\\Temp\\aidl7553315170959235872.d C:\\Users\\Lenovo\\StudioProjects\\com.catsmoker.app\\app\\src\\main\\aidl\\com\\catsmoker\\app\\IFileService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package com.catsmoker.app;
public interface IFileService extends android.os.IInterface
{
  /** Default implementation for IFileService. */
  public static class Default implements com.catsmoker.app.IFileService
  {
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    @Override public int executeCommand(java.lang.String[] command) throws android.os.RemoteException
    {
      return 0;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.catsmoker.app.IFileService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.catsmoker.app.IFileService interface,
     * generating a proxy if needed.
     */
    public static com.catsmoker.app.IFileService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.catsmoker.app.IFileService))) {
        return ((com.catsmoker.app.IFileService)iin);
      }
      return new com.catsmoker.app.IFileService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_executeCommand:
        {
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          int _result = this.executeCommand(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.catsmoker.app.IFileService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public int executeCommand(java.lang.String[] command) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(command);
          boolean _status = mRemote.transact(Stub.TRANSACTION_executeCommand, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_executeCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.catsmoker.app.IFileService";
  public void destroy() throws android.os.RemoteException;
  public int executeCommand(java.lang.String[] command) throws android.os.RemoteException;
}
