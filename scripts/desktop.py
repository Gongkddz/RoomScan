"""RoomScan desktop owner. The project runtime stays isolated beside this EXE."""
import base64
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox
import urllib.request
import uuid

ROOT = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).resolve().parents[1]
ASSETS = Path(getattr(sys, '_MEIPASS', ROOT)) / 'assets'
if getattr(sys, 'frozen', False):
    sys.path.insert(0, str(ROOT / '.tools' / 'pycolmap'))
API = 'http://127.0.0.1:8744'


def own_process_tree(process):
    """Windows closes every descendant when our job handle closes, including on a crash."""
    class BASIC(ctypes.Structure):
        _fields_ = [('process_time', ctypes.c_int64), ('job_time', ctypes.c_int64),
                    ('flags', wintypes.DWORD), ('minimum', ctypes.c_size_t), ('maximum', ctypes.c_size_t),
                    ('active', wintypes.DWORD), ('affinity', ctypes.c_size_t),
                    ('priority', wintypes.DWORD), ('scheduling', wintypes.DWORD)]
    class IO(ctypes.Structure):
        _fields_ = [(name, ctypes.c_uint64) for name in ['read_ops','write_ops','other_ops','read_bytes','write_bytes','other_bytes']]
    class LIMIT(ctypes.Structure):
        _fields_ = [('basic', BASIC), ('io', IO), ('process_memory', ctypes.c_size_t),
                    ('job_memory', ctypes.c_size_t), ('peak_process', ctypes.c_size_t), ('peak_job', ctypes.c_size_t)]
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    job = kernel.CreateJobObjectW(None, None)
    limits = LIMIT(); limits.basic.flags = 0x2000
    if not job or not kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
        raise ctypes.WinError(ctypes.get_last_error())
    if not kernel.AssignProcessToJobObject(job, wintypes.HANDLE(int(process._handle))):
        kernel.CloseHandle(job)
        raise ctypes.WinError(ctypes.get_last_error())
    return kernel, job


class Desktop:
    def __init__(self):
        self.window = tk.Tk(); self.window.title('留境 · 电脑服务')
        self.window.geometry('820x660'); self.window.minsize(760,620)
        self.window.configure(bg='#F3F6FA')
        self.window.iconbitmap(str(ASSETS/'roomscan.ico'))
        self.process = None; self.job = None; self.closing = False; self.loaded_qr = False
        self.stop_file = ROOT/'.runtime'/('desktop-stop-'+uuid.uuid4().hex)
        top = tk.Frame(self.window,bg='#12243B',padx=28,pady=20); top.pack(fill='x')
        self.logo = tk.PhotoImage(file=str(ASSETS/'roomscan.png')).subsample(16)
        tk.Label(top,image=self.logo,bg='#12243B').pack(side='left',padx=(0,14))
        tk.Label(top,text='留境  ROOMSCAN',font=('Microsoft YaHei UI',22,'bold'),fg='white',bg='#12243B').pack(side='left')
        tk.Label(self.window,text='把空间，交给留境。',font=('Microsoft YaHei UI',23,'bold'),bg='#F3F6FA',fg='#12243B').pack(anchor='w',padx=30,pady=(24,8))
        self.status = tk.Label(self.window,text='正在启动电脑服务…',font=('Microsoft YaHei UI',11),bg='#F3F6FA',fg='#42746B'); self.status.pack(anchor='w',padx=32)
        body=tk.Frame(self.window,bg='white',padx=20,pady=16);body.pack(fill='both',expand=True,padx=30,pady=18)
        left=tk.Frame(body,bg='white');left.pack(side='left',fill='both',expand=True)
        tk.Label(left,text='手机扫码配对',font=('Microsoft YaHei UI',15,'bold'),bg='white').pack()
        self.qr=tk.Label(left,bg='white',text='正在准备…');self.qr.pack(pady=8)
        self.expiry=tk.Label(left,text='',bg='white',fg='#78859B');self.expiry.pack()
        tk.Button(left,text='刷新配对码',command=self.renew,relief='flat',bg='#E4F6EF',padx=20,pady=7).pack(pady=8)
        self.details=tk.Label(body,text='正在读取任务…',justify='left',anchor='nw',wraplength=290,font=('Microsoft YaHei UI',11),bg='white',fg='#26394F');self.details.pack(side='right',fill='both',expand=True,padx=20,pady=10)
        tk.Label(self.window,text='关闭此窗口将停止电脑服务与正在执行的重建任务。\n手机上已保存的录制和模型会保留。',bg='#F3F6FA',fg='#78859B',font=('Microsoft YaHei UI',10)).pack(side='bottom',before=body,pady=(0,18))
        self.window.protocol('WM_DELETE_WINDOW',self.close)
        self.window.after(100,self.prepare_runtime)

    def prepare_runtime(self):
        if not getattr(sys, 'frozen', False) or (ROOT/'.tools/pycolmap/pycolmap/__init__.py').is_file():
            self.start(); return
        self.status.config(text='首次启动：正在下载并校验位姿引擎，需要联网…')
        self.engine_done = False
        self.engine_error = None
        def install():
            try:
                from roomscan.engine_setup import ensure_engine
                ensure_engine(ROOT, ASSETS/'pycolmap-wheel.json')
            except Exception as error:
                self.engine_error = str(error)
            finally:
                self.engine_done = True
        threading.Thread(target=install, daemon=True).start()
        self.wait_engine()

    def wait_engine(self):
        if self.closing: return
        if not self.engine_done:
            self.window.after(200,self.wait_engine); return
        if self.engine_error:
            messagebox.showwarning('位姿引擎尚未安装','请检查网络后重新打开留境。\n电脑服务仍可用于配对与同步。\n'+self.engine_error,parent=self.window)
        self.start()

    def request(self,path,post=False):
        headers={'Origin':API} if post else {}
        request=urllib.request.Request(API+path,headers=headers,method='POST' if post else 'GET')
        # Loopback traffic must bypass any configured HTTP proxy.
        return urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=1.5).read()

    def start(self):
        try:
            if getattr(sys, 'frozen', False):
                service_command = [sys.executable, '--roomscan-cli']
            else:
                python=ROOT/'.venv/Scripts/python.exe'
                if not python.is_file(): raise RuntimeError('请先创建项目 .venv 并安装依赖。')
                service_command = [str(python), '-m', 'roomscan.cli']
            for port in (8743,8744):
                with socket.socket() as probe:
                    if probe.connect_ex(('127.0.0.1',port))==0:
                        raise RuntimeError('已有电脑服务占用端口。请先关闭旧服务，再打开留境。')
            command=['powershell.exe','-NoProfile','-Command',
                     '$n=@(Get-NetAdapter -Physical | Where-Object Status -eq Up | Select-Object -ExpandProperty Name); '
                     '(Get-NetIPConfiguration | Where-Object {$_.InterfaceAlias -in $n -and $_.IPv4DefaultGateway -and $_.IPv4Address} | Select-Object -First 1).IPv4Address.IPAddress']
            ip=subprocess.check_output(command,creationflags=subprocess.CREATE_NO_WINDOW,text=True,timeout=15).strip()
            socket.inet_aton(ip)
            self.stop_file.parent.mkdir(exist_ok=True)
            with (ROOT/'.runtime/server.stdout.log').open('w') as out,(ROOT/'.runtime/server.stderr.log').open('w') as err:
                self.process=subprocess.Popen(service_command+['serve','--advertise',ip,'--stop-file',str(self.stop_file)],cwd=ROOT,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
            self.job=own_process_tree(self.process)
            self.status.config(text='服务正在启动 · '+ip)
            self.started=time.monotonic();self.poll()
        except Exception as error:
            messagebox.showerror('留境启动失败',str(error),parent=self.window);self.close()

    def renew(self):
        try: self.request('/api/pairing',True);self.loaded_qr=False
        except Exception: self.status.config(text='暂时无法刷新，请等待服务就绪')

    def poll(self):
        if self.closing:return
        if self.process.poll() is not None:
            self.status.config(text='服务已停止，请关闭窗口后重新打开');return
        try:
            data=json.loads(self.request('/api/status'))
            self.status.config(text='● 电脑服务运行中 · '+('自动重建已就绪' if data['training_ready'] else '等待训练引擎'))
            seconds=data['pairing_seconds']
            if seconds>0 and not self.loaded_qr:
                self.qr_image=tk.PhotoImage(data=base64.b64encode(self.request('/pairing.png')))
                self.qr.config(image=self.qr_image,text='');self.loaded_qr=True
            if seconds<=0:self.qr.config(image='',text='配对码已使用或过期');self.loaded_qr=False
            self.expiry.config(text=f'有效期 {seconds//60}:{seconds%60:02}' if seconds else '已配对手机会自动重连')
            names='、'.join(d['name'] for d in data['devices']) or '等待手机连接'
            stages={'preparing':'检查扫描记录','features':'提取画面特征','matching':'关联视角','mapping':'恢复空间结构','undistorting':'准备训练','training':'生成高斯场景','ready':'已完成 · 手机可下载','failed':'处理失败','paused':'已停止'}
            jobs='\n\n'.join(stages.get(j['stage'],j['state']) for j in data['jobs'][:5]) or '等待手机同步扫描记录'
            self.details.config(text=f"已配对设备\n{names}\n\n空间记录  {data['projects']}\n\n处理任务\n{jobs}")
        except Exception:
            if time.monotonic()-self.started>25:self.status.config(text='服务连接异常，请查看 .runtime 中的日志')
        self.window.after(1800,self.poll)

    def close(self):
        if self.closing:return
        self.closing=True;self.status.config(text='正在停止服务和重建进程…')
        self.stop_file.parent.mkdir(exist_ok=True);self.stop_file.touch()
        def stop():
            if self.process:
                try:self.process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    if not self.job:self.process.kill()
            if self.job:self.job[0].CloseHandle(self.job[1])
            self.stop_file.unlink(missing_ok=True)
            self.stopped=True
        self.stopped=False;threading.Thread(target=stop,daemon=True).start();self.finish_close()

    def finish_close(self):
        if self.stopped:self.window.destroy()
        else:self.window.after(100,self.finish_close)


def run_background(mode):
    if mode == '--install-training':
        from roomscan.engine_setup import ensure_engine
        installed = ensure_engine(ROOT, ASSETS/'pycolmap-wheel.json')
        import importlib
        importlib.invalidate_caches()
        sys.path_importer_cache.pop(str(installed), None)
        import pycolmap
        (ROOT/'.runtime/engine-check.json').write_text(json.dumps({'version':pycolmap.__version__}),encoding='utf-8')
    elif mode == '--roomscan-cli':
        from roomscan.cli import main
        main()
    else:
        import runpy
        runpy.run_module('roomscan.pipeline', run_name='__main__')


if __name__=='__main__':
    # Frozen subprocesses share this entry point and bundled Python runtime.
    if len(sys.argv) > 1 and sys.argv[1] in {'--roomscan-cli', '--roomscan-pipeline', '--install-training'}:
        if sys.stdout is None:
            (ROOT / '.runtime').mkdir(exist_ok=True)
            sys.stdout = (ROOT / '.runtime' / f'process-{os.getpid()}.log').open('a', encoding='utf-8', buffering=1)
            sys.stderr = sys.stdout
        mode = sys.argv.pop(1)
        try:
            run_background(mode)
        except Exception:
            import traceback
            traceback.print_exc()
            sys.exit(1)
        sys.exit(0)
    # Only one window may own the service and its process tree.
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.CreateMutexW.restype=wintypes.HANDLE
    mutex=kernel.CreateMutexW(None,False,'Local\\RoomScanDesktop')
    if ctypes.get_last_error()==183:
        ctypes.windll.user32.MessageBoxW(None,'留境已经打开，请使用现有窗口。','留境',0)
        sys.exit(0)
    Desktop().window.mainloop()
