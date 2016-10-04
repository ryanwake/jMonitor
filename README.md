# jMonitor
Project for Senior Seminar class.

A monitoring software for macOS made in Java.
The project utilizes the [Oshi](http://github.com/oshi/oshi) library to get different information about the computer.
Oshi supports macOS, Windows, Linux so the project can easily be extended to support all OS's.
But in order to keep it within a timeframe for a class project I limited myself to only macOS computers.

The project will consist of two parts, the first one being the data gathering backend that is written in Java.
The backend will gather the data and send it off to a MySQL database, currently the database is just connected to straight from Java.
If time allows I will create a RESTful API in order to save the data, but for now that's how it works.

The second part of the project will be a dashboard for viewing the data that is stored in database.
My current idea of the dashboard will be written in some form of JavaScript, haven't decided.