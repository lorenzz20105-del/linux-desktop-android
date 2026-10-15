#!/usr/bin/env python3
# Login screen for the desktop, shown full screen like a PC sign-in page.
# First run: asks you to create a password. After that: asks for it.
# Exits with status 0 once the right password is entered (or created), which unlocks the desktop.
import hashlib
import hmac
import os
import sys
import time
import tkinter as tk

PASSWD = os.path.expanduser('~/.config/desktop-login/passwd')
ITERATIONS = 50000
USER = 'root'


def hash_pw(pw, salt):
    return hashlib.pbkdf2_hmac('sha256', pw.encode('utf-8'), salt, ITERATIONS)


def load_hash():
    try:
        with open(PASSWD) as f:
            scheme, iters, salt_hex, hash_hex = f.read().strip().split('$')
        if scheme != 'pbkdf2_sha256':
            return None
        return bytes.fromhex(salt_hex), bytes.fromhex(hash_hex)
    except (OSError, ValueError):
        return None


def save_password(pw):
    os.makedirs(os.path.dirname(PASSWD), exist_ok=True)
    salt = os.urandom(16)
    with open(PASSWD, 'w') as f:
        f.write('pbkdf2_sha256$%d$%s$%s\n' % (ITERATIONS, salt.hex(), hash_pw(pw, salt).hex()))
    os.chmod(PASSWD, 0o600)


class Greeter:
    BG = '#0b1f3a'
    FG = '#ffffff'
    MUTED = '#9fb3d1'
    ERR = '#ff6b6b'

    def __init__(self, root, setup):
        self.root = root
        self.setup = setup
        root.title('Sign in')
        root.configure(bg=self.BG)
        root.attributes('-fullscreen', True)
        root.attributes('-topmost', True)
        root.protocol('WM_DELETE_WINDOW', lambda: None)  # The screen cannot be closed, only signed in.

        self.clock = tk.Label(root, bg=self.BG, fg=self.FG, font=('DejaVu Sans', 40, 'bold'))
        self.clock.pack(pady=(40, 0))
        self.date = tk.Label(root, bg=self.BG, fg=self.MUTED, font=('DejaVu Sans', 14))
        self.date.pack()

        box = tk.Frame(root, bg=self.BG)
        box.pack(expand=True)

        avatar = tk.Canvas(box, width=130, height=130, bg=self.BG, highlightthickness=0)
        avatar.create_oval(5, 5, 125, 125, fill='#2f6fd6', outline='')
        avatar.create_text(65, 65, text=USER[:1].upper(), fill=self.FG, font=('DejaVu Sans', 52, 'bold'))
        avatar.pack()

        tk.Label(box, text=USER, bg=self.BG, fg=self.FG, font=('DejaVu Sans', 22, 'bold')).pack(pady=(14, 8))

        if setup:
            tk.Label(box, text='Create a password for this desktop', bg=self.BG, fg=self.MUTED,
                     font=('DejaVu Sans', 13)).pack(pady=(0, 10))
        self.entry = tk.Entry(box, show='\u2022', width=24, font=('DejaVu Sans', 18), justify='center',
                              relief='flat', bg='#ffffff', fg='#000000', insertbackground='#000000')
        self.entry.pack(ipady=6)
        self.confirm = None
        if setup:
            tk.Label(box, text='Confirm password', bg=self.BG, fg=self.MUTED,
                     font=('DejaVu Sans', 13)).pack(pady=(12, 6))
            self.confirm = tk.Entry(box, show='\u2022', width=24, font=('DejaVu Sans', 18), justify='center',
                                    relief='flat', bg='#ffffff', fg='#000000', insertbackground='#000000')
            self.confirm.pack(ipady=6)

        self.msg = tk.Label(box, text='', bg=self.BG, fg=self.ERR, font=('DejaVu Sans', 13))
        self.msg.pack(pady=(12, 0))

        tk.Button(box, text='Create and continue' if setup else 'Sign in', command=self.submit,
                  bg='#2f6fd6', fg=self.FG, activebackground='#2259ad', activeforeground=self.FG,
                  relief='flat', font=('DejaVu Sans', 14, 'bold'), padx=24, pady=8).pack(pady=(10, 0))

        self.entry.bind('<Return>', self.submit)
        if self.confirm is not None:
            self.confirm.bind('<Return>', self.submit)
        self.hash = None if setup else load_hash()
        self.tick()
        self.entry.focus_force()

    def tick(self):
        self.clock.config(text=time.strftime('%H:%M'))
        self.date.config(text=time.strftime('%A, %B %d'))
        self.root.after(1000, self.tick)

    def done(self):
        self.root.destroy()
        sys.exit(0)

    def fail(self, text):
        self.msg.config(text=text)
        self.entry.delete(0, 'end')
        if self.confirm is not None:
            self.confirm.delete(0, 'end')
        self.entry.config(state='disabled')
        self.root.after(1500, lambda: (self.entry.config(state='normal'), self.entry.focus_force()))

    def submit(self, _event=None):
        pw = self.entry.get()
        if self.setup:
            if len(pw) < 4:
                return self.fail('Use at least 4 characters')
            if pw != self.confirm.get():
                return self.fail('The passwords do not match')
            save_password(pw)
            return self.done()
        if self.hash is None:
            # The password file went missing. Ask for a new one.
            self.setup = True
            return self.fail('No password is set. Restart the desktop to create one.')
        salt, expected = self.hash
        if hmac.compare_digest(hash_pw(pw, salt), expected):
            return self.done()
        return self.fail('Wrong password, try again')


def main():
    if os.environ.get('DISPLAY') is None:
        print('desktop-login: DISPLAY is not set', file=sys.stderr)
        sys.exit(1)
    root = tk.Tk()
    Greeter(root, setup=load_hash() is None)
    root.mainloop()


if __name__ == '__main__':
    main()
