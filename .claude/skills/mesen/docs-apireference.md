Lua API reference :: Mesen Documentation
    
    
    
    
    
    
    
    
    
    
	
    
      
    

    
    
    
    
  
  
    





        
        
        
              
              
                
                
                
                
                    
                        
                          
                        
                    
                  
                  
                  
                  
                    
          
          
            
            
          
          
            Home > Lua API reference
          
         
          
           
                  
                
                
                    
    

    


                
              
            
            

        
        
          
            Lua API reference
          

        

     



This section documents the Mesen-specific Lua API that is available in scripts via the script window.

Changelog

Lua scripting is still a relatively recent feature and the API is not quite stable yet. To get a list of the major changes between different versions of Mesen, take a look at the Changelog.

API References


Callbacks
Drawing
Emulation
Input
Logging
Memory Access
Miscellaneous
Enums


Additional features

Test Runner Mode

Mesen can be started in a headless test runner mode that can be used to implement automated testing by using Lua scripts.

To start Mesen in headless mode, use the --testrunner command line option and specify both a game and a Lua script to run:

Mesen.exe --testrunner MyGame.nes MyTest.lua


This will start Mesen (headless), load the game and the Lua script and start executing the game at maximum speed until the Lua script calls the emu.stop() function. The emu.stop() function can specify an exit code, which will be returned by the Mesen process, which can be used to validate whether the test passed or failed.

LuaSocket

The Lua implementation found in Mesen has a version of LuaSocket (GitHub) built into it.  The socket and mime packages are available and can be accessed by using local socket = require("socket.core") and local mime = require("mime.core"), respectively.

See LuaSocket’s documentation for more information on how to use this library.

Here is a tiny TCP socket sample that connects to google.com via HTTP and downloads the page:

local socket = require("socket.core")
local tcp = sock.tcp()

--Set a 2-second timeout for all request, otherwise the process could hang!
tcp:settimeout(2)
local res = tcp:connect("www.google.com", 80)
tcp:send("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")

local text
repeat
   text = tcp:receive()  
   emu.log(text)
until text == nil


Using sockets without calling the settimeout(seconds) function (and specifying a reasonable number of seconds) first can result in the Mesen process hanging until the socket finishes the operation it is waiting for.
For this reason, it is highly recommended to ALWAYS call settimeout(seconds) on any newly created TCP/etc object before calling any other function on it.